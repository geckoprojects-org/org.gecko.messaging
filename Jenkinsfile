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
        // Jenkins only builds and publishes. All tests that need brokers (OSGi integration tests
        // and the TCK runs) run in GitHub Actions (.github/workflows/build.yml), which is meant to
        // gate main and snapshot. This stage only compiles and assembles the bundles and runs the
        // plain JUnit tests.
        stage('Build') {
            steps {
                sh './gradlew clean build --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2 --no-daemon'
            }
            post {
                always {
                    junit testResults: '**/generated/test-reports/**/TEST-*.xml', allowEmptyResults: true, skipPublishingChecks: true
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
