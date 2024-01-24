# RabbitMQ Docker

To start the container via docker use the following command:

```
docker run -d --hostname rabbitmq01 --name rabbitmq01 -p 8080:15672 -p 5672:5672 -e RABBITMQ_DEFAULT_USER=demo -e RABBITMQ_DEFAULT_PASS=1234 -e RABBITMQ_DEFAULT_HOST=test rabbitmq:3-management
```

This start the docker container, including the management console, which is available under http://localhost:8080 using username **demo** and password **1234**

# Test configuration

A test configuration is located in the json-file '*rabbit_config.json*' in this folder an can be imported into the container using the **Import definitions** section in the **Overview** tab of the management console.