#!/bin/bash
# Starts and stops the brokers of the TCK runs: Mosquitto 2 (MQTT) and RabbitMQ (AMQP 0-9-1).
# Works with docker and podman. Host ports are chosen away from the defaults, so a broker that
# runs on the machine already does not get in the way:
#   MQTT  ${MQTT_PORT:-11883}   AMQP  ${AMQP_PORT:-15675}
#
#   docker/brokers.sh up | down | status
set -eu
DIR=$(cd "$(dirname "$0")" && pwd)
RUNTIME=${CONTAINER_RUNTIME:-$(command -v docker >/dev/null 2>&1 && echo docker || echo podman)}
MQTT_PORT=${MQTT_PORT:-11883}
AMQP_PORT=${AMQP_PORT:-15675}
MOSQUITTO_IMAGE=${MOSQUITTO_IMAGE:-docker.io/library/eclipse-mosquitto:2.0.20}
RABBITMQ_IMAGE=${RABBITMQ_IMAGE:-docker.io/library/rabbitmq:4.1-alpine}

fail() {
	echo "$1" >&2
	for c in gecko-msg-mosquitto gecko-msg-rabbitmq; do
		echo "--- $c: $($RUNTIME ps -a --filter name=$c --format '{{.Status}}')" >&2
		$RUNTIME logs --tail 30 "$c" >&2 2>&1 || true
	done
	exit 1
}

wait_port() {
	i=0
	while ! (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; do
		i=$((i + 1))
		[ "$i" -gt 60 ] && fail "port $1 did not open"
		sleep 1
	done
}

case "${1:-}" in
up)
	$RUNTIME rm -f gecko-msg-mosquitto gecko-msg-rabbitmq >/dev/null 2>&1 || true
	# No --rm, so the logs of a container that died stay readable; down removes the containers.
	# The config goes in as an environment variable and is written inside the container: a bind
	# mount or `cp` keeps the owner and mode of the checkout, which differ between developer
	# machines and CI agents (the Jenkins agent's file was unreadable for the mosquitto user).
	# Running as mosquitto, the owner of /mosquitto/config, works without any capability; the
	# Jenkins agent's podman does not give root in the container CAP_DAC_OVERRIDE.
	$RUNTIME run -d --name gecko-msg-mosquitto --user mosquitto -p "$MQTT_PORT:1883" \
		-e MOSQUITTO_CONF="$(cat "$DIR/mosquitto.conf")" "$MOSQUITTO_IMAGE" \
		sh -c 'printf "%s\n" "$MOSQUITTO_CONF" > /mosquitto/config/tck.conf && exec mosquitto -c /mosquitto/config/tck.conf' >/dev/null
	$RUNTIME run -d --name gecko-msg-rabbitmq -p "$AMQP_PORT:5672" "$RABBITMQ_IMAGE" >/dev/null
	wait_port "$MQTT_PORT"
	# RabbitMQ opens the port before it accepts connections; wait for the node to be up. Not via
	# `exec rabbitmq-diagnostics`: that runs as root and, early enough, creates .erlang.cookie
	# owned by root, which the server (user rabbitmq) then cannot read and dies.
	i=0
	until $RUNTIME logs gecko-msg-rabbitmq 2>&1 | grep -q 'Server startup complete'; do
		i=$((i + 1))
		[ "$i" -gt 90 ] && fail "RabbitMQ did not come up"
		sleep 1
	done
	echo "brokers up: mqtt tcp://localhost:$MQTT_PORT, amqp amqp://localhost:$AMQP_PORT"
	;;
down)
	$RUNTIME rm -f gecko-msg-mosquitto gecko-msg-rabbitmq >/dev/null 2>&1 || true
	echo "brokers down"
	;;
status)
	$RUNTIME ps --filter name=gecko-msg- --format '{{.Names}} {{.Status}}'
	;;
*)
	echo "usage: $0 up|down|status" >&2
	exit 2
	;;
esac
