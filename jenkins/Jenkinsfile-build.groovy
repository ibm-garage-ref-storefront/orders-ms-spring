/*
    To learn how to use this sample pipeline, follow the guide below and enter the
    corresponding values for your environment and for this repository:
    - https://github.com/ibm-cloud-architecture/refarch-cloudnative-devops-kubernetes
*/

// Environment
def clusterURL = env.CLUSTER_URL
def clusterAccountId = env.CLUSTER_ACCOUNT_ID
def clusterCredentialId = env.CLUSTER_CREDENTIAL_ID ?: "cluster-credentials"

// Pod Template
def podLabel = "orders"
def cloud = env.CLOUD ?: "kubernetes"
def registryCredsID = env.REGISTRY_CREDENTIALS ?: "registry-credentials-id"
def serviceAccount = env.SERVICE_ACCOUNT ?: "jenkins"

// Pod Environment Variables
def namespace = env.NAMESPACE ?: "default"
def registry = env.REGISTRY ?: "docker.io"
def imageName = env.IMAGE_NAME ?: "ibmcase/bluecompute-orders"
def serviceLabels = env.SERVICE_LABELS ?: "app=orders,tier=backend" //,version=v1"
def microServiceName = env.MICROSERVICE_NAME ?: "orders"
def servicePort = env.MICROSERVICE_PORT ?: "8084"
def managementPort = env.MANAGEMENT_PORT ?: "8094"

// External Test Database Parameters
// For username and passwords, set MYSQL_USER (as string parameter) and MYSQL_PASSWORD (as password parameter)
//     - These variables get picked up by the Java application automatically
//     - There were issues with Jenkins credentials plugin interfering with setting up the password directly

def mariaDBHost = env.MYSQL_HOST
def mariaDBPort = env.MYSQL_PORT ?: "3306"
def mariaDBDatabase = env.MYSQL_DATABASE ?: "ordersdb"

// HS256_KEY Secret
def hs256Key = env.HS256_KEY

/*
  Optional Pod Environment Variables
 */
def helmHome = env.HELM_HOME ?: env.JENKINS_HOME + "/.helm"

podTemplate(label: podLabel, cloud: cloud, serviceAccount: serviceAccount, envVars: [
        envVar(key: 'CLUSTER_URL', value: clusterURL),
        envVar(key: 'CLUSTER_ACCOUNT_ID', value: clusterAccountId),
        envVar(key: 'NAMESPACE', value: namespace),
        envVar(key: 'REGISTRY', value: registry),
        envVar(key: 'IMAGE_NAME', value: imageName),
        envVar(key: 'SERVICE_LABELS', value: serviceLabels),
        envVar(key: 'MICROSERVICE_NAME', value: microServiceName),
        envVar(key: 'MICROSERVICE_PORT', value: servicePort),
        envVar(key: 'MANAGEMENT_PORT', value: managementPort),
        envVar(key: 'MYSQL_HOST', value: mariaDBHost),
        envVar(key: 'MYSQL_PORT', value: mariaDBPort),
        envVar(key: 'MYSQL_DATABASE', value: mariaDBDatabase),
        envVar(key: 'HS256_KEY', value: hs256Key),
        envVar(key: 'HELM_HOME', value: helmHome)
    ],
    volumes: [
        hostPathVolume(mountPath: '/home/gradle/.gradle', hostPath: '/tmp/jenkins/.gradle'),
        emptyDirVolume(mountPath: '/var/lib/docker', memory: false)
    ],
    containers: [
        containerTemplate(name: 'jdk', image: 'ibmcase/openjdk-bash:alpine', ttyEnabled: true, command: 'cat'),
        containerTemplate(name: 'docker', image: 'ibmcase/docker:18.09-dind', privileged: true)
  ]) {

    node(podLabel) {
        checkout scm

        // Local
        container(name:'jdk', shell:'/bin/bash') {
            stage('Local - Build and Unit Test') {
                sh """
                #!/bin/bash
                ./gradlew build
                """
            }
            stage('Local - Run and Test') {
                sh """
                #!/bin/bash

                JAVA_OPTS="-Dspring.datasource.url=jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}"
                JAVA_OPTS="\${JAVA_OPTS} -Dspring.datasource.port=${MYSQL_PORT}"
                JAVA_OPTS="\${JAVA_OPTS} -Dserver.port=${MICROSERVICE_PORT}"

                java \${JAVA_OPTS} -jar build/libs/micro-orders-0.0.1.jar &

                PID=`echo \$!`

                # Let the application start
                bash scripts/health_check.sh "http://127.0.0.1:${MANAGEMENT_PORT}"

                # Run tests
                bash scripts/api_tests.sh 127.0.0.1 ${MICROSERVICE_PORT}

                # Kill process
                kill \${PID}
                """
            }
        }

        // Docker
        container(name:'docker', shell:'/bin/bash') {
            stage('Docker - Build Image') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" == "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                docker build -t \${IMAGE} .
                """
            }
            stage('Docker - Run and Test') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" == "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                fi

                # Kill Container if it already exists
                docker kill ${MICROSERVICE_NAME} || true
                docker rm ${MICROSERVICE_NAME} || true

                # Start Container
                echo "Starting ${MICROSERVICE_NAME} container"
                set +x
                docker run --name ${MICROSERVICE_NAME} -d \
                    -p ${MICROSERVICE_PORT}:${MICROSERVICE_PORT} \
                    -p ${MANAGEMENT_PORT}:${MANAGEMENT_PORT} \
                    -e SERVICE_PORT=${MICROSERVICE_PORT} \
                    -e MYSQL_HOST=${MYSQL_HOST} \
                    -e MYSQL_PORT=${MYSQL_PORT} \
                    -e MYSQL_USER=${MYSQL_USER} \
                    -e MYSQL_PASSWORD=${MYSQL_PASSWORD} \
                    -e MYSQL_DATABASE=${MYSQL_DATABASE} \
                    -e HS256_KEY=${HS256_KEY} \
                    \${IMAGE}
                set -x

                # Check that application started successfully
                docker ps

                # Check the logs
                docker logs -f ${MICROSERVICE_NAME} &
                PID=`echo \$!`

                # Get the container IP Address
                CONTAINER_IP=`docker inspect ${MICROSERVICE_NAME} | jq -r '.[0].NetworkSettings.IPAddress'`

                # Let the application start
                bash scripts/health_check.sh "http://\${CONTAINER_IP}:${MANAGEMENT_PORT}"

                # Run tests
                bash scripts/api_tests.sh \${CONTAINER_IP} ${MICROSERVICE_PORT}

                # Kill process
                kill \${PID}

                # Kill Container
                docker kill ${MICROSERVICE_NAME} || true
                docker rm ${MICROSERVICE_NAME} || true
                """
            }
            stage('Docker - Push Image to Registry') {
                withCredentials([usernamePassword(credentialsId: registryCredsID,
                                               usernameVariable: 'USERNAME',
                                               passwordVariable: 'PASSWORD')]) {
                    sh """
                    #!/bin/bash

                    # Get image
                    if [ "${REGISTRY}" == "docker.io" ]; then
                        IMAGE=${IMAGE_NAME}:${env.BUILD_NUMBER}
                    else
                        IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${env.BUILD_NUMBER}
                    fi

                    docker login -u ${USERNAME} -p ${PASSWORD} ${REGISTRY}

                    docker push \${IMAGE}
                    """
                }
            }
        }
    }
}
