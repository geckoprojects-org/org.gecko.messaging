pipeline  {
    agent any

    tools {
        jdk 'OpenJDK17'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
    }

    stages {
        stage('Unit & Integration Tests') {
            steps {
                script {
                    try {
                        sh './gradlew clean testOSGi --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2 --no-daemon' 
                    } finally {
                        junit testResults: '**/generated/test-reports/testOSGi/TEST-*.xml', skipPublishingChecks: true //make the junit test results available in any case (success & failure)
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
                sh "./gradlew build release -Drelease.dir=$JENKINS_HOME/repo.gecko/release/org.gecko.messaging --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
            }
        }
        stage('Snapshot branch release') {
            when { 
                branch 'develop'
            }
            steps  {
                echo "I am building on ${env.JOB_NAME}"
                sh "./gradlew release --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
                sh "mkdir -p $JENKINS_HOME/repo.gecko/snapshot/org.gecko.messaging"
                sh "rm -rf $JENKINS_HOME/repo.gecko/snapshot/org.gecko.messaging/*"
                sh "cp -r cnf/release/* $JENKINS_HOME/repo.gecko/snapshot/org.gecko.messaging"
            }
        }
        stage('Aicas branch release') {
            when { 
                branch 'aicas'
            }
            steps  {
                echo "I am building on ${env.JOB_NAME}"
                sh "./gradlew release --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
            }
        }
        stage('Other branch') {
            when {
                allOf {
                    not {
                        branch 'develop'
                    }
                    not {
                        branch 'main'
                    }
                    not {
                        branch 'aicas'
                    }
                }
            }
            steps  {
                echo "I am building on ${env.JOB_NAME}"
                sh "./gradlew build --info --stacktrace -Dmaven.repo.local=${WORKSPACE}/.m2"
            }
        }

    }

}
