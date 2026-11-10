#!/bin/bash
# Starts and stops the brokers of the TCK runs: Mosquitto 2 (MQTT) and RabbitMQ (AMQP 0-9-1).
# Works with docker and podman. Host ports are chosen away from the defaults, so a broker that
# runs on the machine already does not get in the way:
#   MQTT  ${MQTT_PORT:-11883}   AMQP  ${AMQP_PORT:-15675}
# Both brokers also listen over TLS and demand a client certificate there (mutual TLS):
#   MQTT  ${MQTT_TLS_PORT:-11887}   AMQP  ${AMQP_TLS_PORT:-15677}
# The certificates are made anew on every `up` (openssl, and keytool of the JDK for the trust
# store) into ${TLS_DIR:-build/tck-tls}: a CA, the broker certificate for localhost, and for the
# TCK client.p12 (client certificate and key) and truststore.p12 (the CA), password tck-tls.
#
#   docker/brokers.sh up | down | status
set -eu
DIR=$(cd "$(dirname "$0")" && pwd)
RUNTIME=${CONTAINER_RUNTIME:-$(command -v docker >/dev/null 2>&1 && echo docker || echo podman)}
MQTT_PORT=${MQTT_PORT:-11883}
AMQP_PORT=${AMQP_PORT:-15675}
MQTT_TLS_PORT=${MQTT_TLS_PORT:-11887}
AMQP_TLS_PORT=${AMQP_TLS_PORT:-15677}
TLS_DIR=${TLS_DIR:-$DIR/../build/tck-tls}
TLS_PASSWORD=tck-tls
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

# A CA, a broker certificate for localhost and a client certificate, both signed by the CA; the
# stores of the client as PKCS12. Test material: made on every `up`, valid for 30 days.
make_certificates() {
	command -v openssl >/dev/null 2>&1 || fail "openssl is needed for the certificates of the TLS listeners"
	command -v keytool >/dev/null 2>&1 || fail "keytool (JDK) is needed for the trust store of the TLS runs"
	rm -rf "$TLS_DIR"
	mkdir -p "$TLS_DIR"
	(
		cd "$TLS_DIR"
		openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 \
			-subj "/CN=Gecko Messaging TCK CA" -keyout ca.key -out ca.crt \
			-addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
		printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > server.ext
		printf 'extendedKeyUsage=clientAuth\n' > client.ext
		for who in server client; do
			cn=$([ "$who" = server ] && echo localhost || echo tck-client)
			openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj "/CN=$cn" \
				-keyout "$who.key" -out "$who.csr"
			openssl x509 -req -in "$who.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 \
				-extfile "$who.ext" -out "$who.crt"
		done
		openssl pkcs12 -export -name client -inkey client.key -in client.crt -certfile ca.crt \
			-passout "pass:$TLS_PASSWORD" -out client.p12
		# keytool marks the certificate as trusted; a PKCS12 file of openssl with only a
		# certificate holds no trusted entry for Java
		keytool -importcert -noprompt -alias ca -file ca.crt -storetype PKCS12 \
			-storepass "$TLS_PASSWORD" -keystore truststore.p12
	) >"$TLS_DIR.log" 2>&1 || { cat "$TLS_DIR.log" >&2; fail "making the TLS certificates failed"; }
	rm -f "$TLS_DIR.log"
}

case "${1:-}" in
up)
	$RUNTIME rm -f gecko-msg-mosquitto gecko-msg-rabbitmq >/dev/null 2>&1 || true
	make_certificates
	# No --rm, so the logs of a container that died stay readable; down removes the containers.
	# The config goes in as an environment variable and is written inside the container, by
	# mosquitto, the owner of /mosquitto/config: a bind mount or `cp` keeps the owner and mode of
	# the checkout, which differ between machines, and needs no capability this way.
	# The certificates go the same way.
	$RUNTIME run -d --name gecko-msg-mosquitto --user mosquitto -p "$MQTT_PORT:1883" -p "$MQTT_TLS_PORT:8883" \
		-e MOSQUITTO_CONF="$(cat "$DIR/mosquitto.conf")" \
		-e TLS_CA="$(cat "$TLS_DIR/ca.crt")" -e TLS_CERT="$(cat "$TLS_DIR/server.crt")" \
		-e TLS_KEY="$(cat "$TLS_DIR/server.key")" "$MOSQUITTO_IMAGE" \
		sh -c 'cd /mosquitto/config && umask 077 && printf "%s\n" "$TLS_CA" > ca.crt && printf "%s\n" "$TLS_CERT" > server.crt \
			&& printf "%s\n" "$TLS_KEY" > server.key && printf "%s\n" "$MOSQUITTO_CONF" > tck.conf \
			&& exec mosquitto -c /mosquitto/config/tck.conf' >/dev/null
	# user tck/tck: RabbitMQ admits its default user guest only from localhost, and a connection
	# through the port mapping does not come from localhost
	# The TLS listener and its certificates are written before the image's entrypoint starts the
	# server (as root; the entrypoint then changes to the user rabbitmq, hence readable for all).
	$RUNTIME run -d --name gecko-msg-rabbitmq -p "$AMQP_PORT:5672" -p "$AMQP_TLS_PORT:5671" \
		-e RABBITMQ_DEFAULT_USER=tck -e RABBITMQ_DEFAULT_PASS=tck \
		-e TLS_CA="$(cat "$TLS_DIR/ca.crt")" -e TLS_CERT="$(cat "$TLS_DIR/server.crt")" \
		-e TLS_KEY="$(cat "$TLS_DIR/server.key")" -e TLS_CONF="$(cat "$DIR/rabbitmq-tls.conf")" "$RABBITMQ_IMAGE" \
		sh -c 'mkdir -p /etc/rabbitmq/tck-tls /etc/rabbitmq/conf.d && cd /etc/rabbitmq/tck-tls \
			&& printf "%s\n" "$TLS_CA" > ca.crt && printf "%s\n" "$TLS_CERT" > server.crt \
			&& printf "%s\n" "$TLS_KEY" > server.key && chmod 644 ca.crt server.crt server.key \
			&& printf "%s\n" "$TLS_CONF" > /etc/rabbitmq/conf.d/20-tck-tls.conf \
			&& exec docker-entrypoint.sh rabbitmq-server' >/dev/null
	wait_port "$MQTT_PORT"
	wait_port "$MQTT_TLS_PORT"
	# RabbitMQ opens the port before it accepts connections; wait for the node to be up. Not via
	# `exec rabbitmq-diagnostics`: that runs as root and, early enough, creates .erlang.cookie
	# owned by root, which the server (user rabbitmq) then cannot read and dies.
	i=0
	until $RUNTIME logs gecko-msg-rabbitmq 2>&1 | grep -q 'Server startup complete'; do
		i=$((i + 1))
		[ "$i" -gt 90 ] && fail "RabbitMQ did not come up"
		sleep 1
	done
	echo "brokers up: mqtt tcp://localhost:$MQTT_PORT, amqp amqp://tck:tck@localhost:$AMQP_PORT"
	echo "mutual TLS: mqtt ssl://localhost:$MQTT_TLS_PORT, amqp amqps://tck:tck@localhost:$AMQP_TLS_PORT, stores in $TLS_DIR"
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
