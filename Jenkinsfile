pipeline  {
    agent any

    environment {
        JAVA_OPTS = "-Xms2048m -Xmx2048m -XX:MaxMetaspaceSize=1024m ${sh(script:'echo $JAVA_OPTS', returnStdout: true).trim()}"
    }
    tools {
        jdk 'OpenJDK21'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
    }

    stages {
        // The TCK runs against RabbitMQ and Mosquitto (docker/compose.yaml); the WebSocket TCK
        // brings its own Jetty hub. brokerUp/brokerDown start and stop the containers.
        stage('Unit, Integration & TCK Tests') {
            steps {
                script {
                    try {
                        sh './gradlew clean build brokerUp testOSGi --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2 --no-daemon'
                    } finally {
                        sh './gradlew brokerDown --no-daemon || true'
                        junit testResults: '**/generated/test-reports/**/TEST-*.xml', skipPublishingChecks: true
                    }
                }
            }
        }
        stage('Main branch release') {
            when {
                branch 'main'
            }
            steps {
                echo "I am building on ${env.BRANCH_NAME}"
                sh "./gradlew clean release -x test -x testOSGi -Drelease.dir=$JENKINS_HOME/repo.gecko/release/org.gecko.messaging --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
            }
        }
        stage('Snapshot branch release') {
            when {
                branch 'snapshot'
            }
            steps  {
                echo "I am building on ${env.JOB_NAME}"
                sh "./gradlew clean release -x test -x testOSGi --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
                sh "mkdir -p $JENKINS_HOME/repo.gecko/snapshot/org.gecko.messaging"
                sh "rm -rf $JENKINS_HOME/repo.gecko/snapshot/org.gecko.messaging/*"
                sh "cp -r cnf/release/* $JENKINS_HOME/repo.gecko/snapshot/org.gecko.messaging"
            }
        }
    }
}
