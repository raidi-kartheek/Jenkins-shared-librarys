// call is the default function name
def call(Map configMap){

pipeline {

    agent {
        node {
            label 'AGENT-1'
        }
    }

    environment {
        ACC_ID = "160885265516"
        appVersion = ""
        PROJECT = configMap.get("project")
        COMPONENT = configMap.get("component")
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    stages {

        stage('Read Version') {
            steps {
                script{
                    def pom = readMavenPom file: 'pom.xml'
                    appVersion = pom.version
                    echo "Application Version: ${appVersion}"
                }
            }
        }

        stage('Build Application') {
            steps {
                script{
                    sh '''
                    mvn clean package
                    '''
                }
            }
        }

        stage('Unit Test') {
            steps {
                script{
                    sh '''
                    mvn test
                    '''
                }
            }
        }

        

        stage('Snyk Dependency Scan') {

            environment {
                SNYK_TOKEN = credentials('snyk-token')
            }

            steps {

                script{

                    sh '''
                    curl -Lo snyk https://static.snyk.io/cli/latest/snyk-linux
                    chmod +x snyk

                    ./snyk auth $SNYK_TOKEN

                    ./snyk test \
                    --file=pom.xml \
                    --severity-threshold=high
                    '''
                }

            }

        }

    

        stage('SonarQube Scan') {

            steps {

                script{

                    def scannerHome = tool 'sonar-8.0'

                    withSonarQubeEnv('sonar-server') {

                        sh """
                        ${scannerHome}/bin/sonar-scanner \
                        -Dsonar.projectKey=${COMPONENT} \
                        -Dsonar.sources=.
                        """

                    }

                }

            }

        }

        stage('Quality Gate') {

            steps {

                timeout(time: 1, unit: 'HOURS') {

                    waitForQualityGate abortPipeline: true

                }

            }

        }

   

        stage('Build Docker Image') {

            steps {

                script{

                    withAWS(region:'us-east-1',credentials:'aws-creds') {

                        sh """

                        aws ecr get-login-password \
                        --region us-east-1 | docker login \
                        --username AWS \
                        --password-stdin ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com

                        docker build \
                        -t ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} .

                        docker push \
                        ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}

                        """

                    }

                }

            }

        }

   

        stage('Trivy Image Scan') {

            steps {

                script{

                    sh """

                    trivy image \
                    --severity HIGH,CRITICAL \
                    --exit-code 1 \
                    ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}

                    """

                }

            }

        }

   
        stage('Deploy to DEV') {

            steps {

                script {

                    build job: "../${COMPONENT}-deploy",

                    wait: false,
                    propagate: false,

                    parameters: [

                        string(name: 'appVersion', value: "${appVersion}"),
                        string(name: 'deploy_to', value: "dev")

                    ]

                }

            }

        }

        // -------------------------
        // DAST Scan
        // -------------------------

        stage('OWASP ZAP DAST Scan') {

            steps {

                script{

                    sh """

                    docker run -t owasp/zap2docker-stable zap-baseline.py \
                    -t http://dev.${COMPONENT}.yourdomain.com \
                    -r zap-report.html

                    """

                }

            }

        }

    }

    post {

        always {

            archiveArtifacts artifacts: 'zap-report.html', fingerprint: true

            cleanWs()

            echo 'Pipeline Completed'

        }

        success {

            echo 'Build Successful'

        }

        failure {

            echo 'Build Failed'

        }

        aborted {

            echo 'Pipeline Aborted'

        }

    }

}
}
