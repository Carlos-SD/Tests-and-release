pipeline {
    agent any

    environment {
        IMAGE_PREFIX   = 'circleguard'
        IMAGE_TAG      = "${env.BUILD_NUMBER}"
        KUBECONFIG     = credentials('kubeconfig')
        DOCKER_API_VERSION = '1.44'
        API_VERSION    = '1.44'
        TESTCONTAINERS_RYUK_DISABLED = 'true'
        TESTCONTAINERS_CHECKS_DISABLE = 'true'
        TESTCONTAINERS_HOST_OVERRIDE = 'host.docker.internal'
    }

    stages {

        // ----------------------------------------------------------------
        // STAGE 1 — Build (all branches)
        // ----------------------------------------------------------------
        stage('Build') {
            steps {
                sh './gradlew clean bootJar -x test'
            }
        }

        // ----------------------------------------------------------------
        // STAGE 2 — Unit Tests (feature/* and dev branches)
        // ----------------------------------------------------------------
        stage('Unit Tests') {
            when {
                anyOf {
                    branch 'feature/*'
                    branch 'dev'
                }
            }
            steps {
                sh './gradlew test -Pexclude=integration,performance,e2e'
            }
            post {
                always {
                    junit '**/build/test-results/test/*.xml'
                }
            }
        }

        // ----------------------------------------------------------------
        // STAGE 3 — Integration Tests (dev and stage branches)
        // ----------------------------------------------------------------
        stage('Integration Tests') {
            when {
                anyOf {
                    branch 'dev'
                    branch 'stage'
                    branch 'master'
                }
            }
            steps {
                sh './gradlew test -Pinclude=integration'
            }
            post {
                always {
                    junit '**/build/test-results/test/*.xml'
                }
            }
        }

        // ----------------------------------------------------------------
        // STAGE 4 — Performance Tests (stage and master branches)
        // ----------------------------------------------------------------
        stage('Performance Tests') {
            when {
                anyOf {
                    branch 'stage'
                    branch 'master'
                }
            }
            steps {
                sh './gradlew test -Pinclude=performance'
            }
            post {
                always {
                    junit '**/build/test-results/test/*.xml'
                }
            }
        }

        // ----------------------------------------------------------------
        // STAGE 4B — Locust Load Tests (stage and master branches)
        // ----------------------------------------------------------------
        stage('Locust Load Tests') {
            when {
                anyOf {
                    branch 'stage'
                    branch 'master'
                }
            }
            steps {
                sh '''
                    if command -v locust >/dev/null 2>&1; then
                        locust -f performance/locustfile.py \
                            --headless \
                            -u ${LOCUST_USERS:-50} \
                            -r ${LOCUST_SPAWN_RATE:-5} \
                            -t ${LOCUST_DURATION:-2m} \
                            --host ${LOCUST_HOST:-http://gateway-service:8087} \
                            --html performance/locust-report.html
                    else
                        echo "Locust is not installed on this Jenkins agent; skipping external load test."
                    fi
                '''
            }
            post {
                always {
                    archiveArtifacts artifacts: 'performance/locust-report.html', allowEmptyArchive: true
                }
            }
        }

        // ----------------------------------------------------------------
        // STAGE 5 — Docker Build (all branches)
        // ----------------------------------------------------------------
        stage('Docker Build & Push') {
            steps {
                script {
                    def services = [
                        'circleguard-auth-service',
                        'circleguard-identity-service',
                        'circleguard-promotion-service',
                        'circleguard-notification-service',
                        'circleguard-form-service',
                        'circleguard-file-service',
                        'circleguard-gateway-service',
                        'circleguard-dashboard-service'
                    ]
                    def shortName = { String s -> s.replace('circleguard-', '').replace('-service', '') }

                    services.each { svc ->
                        def img = "${env.IMAGE_PREFIX}/${shortName(svc)}-service:${env.IMAGE_TAG}"
                        sh "docker build -t ${img} services/${svc}/"
                        sh "docker tag ${img} ${env.IMAGE_PREFIX}/${shortName(svc)}-service:latest"
                    }
                }
            }
        }

        // ----------------------------------------------------------------
        // STAGE 6 — Deploy to Dev (feature/* branches)
        // ----------------------------------------------------------------
        stage('Deploy to Dev') {
            when { branch 'feature/*' }
            steps {
                sh 'kubectl apply -f k8s/namespaces.yaml'
                sh 'kubectl apply -f k8s/infra/ -n circleguard-dev'
                sh 'kubectl rollout status deployment/postgres deployment/redis deployment/neo4j deployment/zookeeper deployment/kafka --namespace=circleguard-dev --timeout=300s'
                sh 'kubectl apply -f k8s/dev/'
                sh 'kubectl rollout status deployment --namespace=circleguard-dev --timeout=120s'
            }
        }

        // ----------------------------------------------------------------
        // STAGE 7 — Deploy to Stage (dev branch)
        // ----------------------------------------------------------------
        stage('Deploy to Stage') {
            when { branch 'dev' }
            steps {
                sh 'kubectl apply -f k8s/namespaces.yaml'
                sh 'kubectl apply -f k8s/infra/ -n circleguard-stage'
                sh 'kubectl rollout status deployment/postgres deployment/redis deployment/neo4j deployment/zookeeper deployment/kafka --namespace=circleguard-stage --timeout=300s'
                sh 'kubectl apply -f k8s/stage/'
                sh 'kubectl rollout status deployment --namespace=circleguard-stage --timeout=120s'
            }
        }

        // ----------------------------------------------------------------
        // STAGE 8 — E2E Tests post-deploy to stage (dev branch)
        // ----------------------------------------------------------------
        stage('E2E Tests') {
            when { branch 'dev' }
            steps {
                sh './gradlew test -Pinclude=e2e'
            }
            post {
                always {
                    junit '**/build/test-results/test/*.xml'
                }
            }
        }

        // ----------------------------------------------------------------
        // STAGE 9 — Deploy to Master (stage branch)
        // ----------------------------------------------------------------
        stage('Deploy to Master') {
            when { branch 'stage' }
            steps {
                sh 'kubectl apply -f k8s/namespaces.yaml'
                sh 'kubectl apply -f k8s/infra/ -n circleguard-master'
                sh 'kubectl rollout status deployment/postgres deployment/redis deployment/neo4j deployment/zookeeper deployment/kafka --namespace=circleguard-master --timeout=300s'
                sh 'kubectl apply -f k8s/master/'
                sh 'kubectl rollout status deployment --namespace=circleguard-master --timeout=180s'
            }
        }

        // ----------------------------------------------------------------
        // STAGE 10 — Release Notes (stage branch → master)
        // ----------------------------------------------------------------
        stage('Release Notes') {
            when { branch 'stage' }
            steps {
                script {
                    def version = "v${env.BUILD_NUMBER}-${new Date().format('yyyy.MM.dd')}"
                    def prevTag  = sh(script: 'git describe --tags --abbrev=0 2>/dev/null || echo ""', returnStdout: true).trim()
                    def logRange = prevTag ? "${prevTag}..HEAD" : 'HEAD'

                    def changelog = sh(
                        script: """
                            git log ${logRange} --pretty=format:'- %s (%an)' \
                                --no-merges \
                                | grep -v '^- Merge'
                        """,
                        returnStdout: true
                    ).trim()

                    def features = sh(
                        script: "echo '${changelog}' | grep -i '^- feat\\|^- add\\|^- new' || true",
                        returnStdout: true
                    ).trim()

                    def fixes = sh(
                        script: "echo '${changelog}' | grep -i '^- fix\\|^- bug\\|^- hotfix' || true",
                        returnStdout: true
                    ).trim()

                    def other = sh(
                        script: "echo '${changelog}' | grep -iv '^- feat\\|^- add\\|^- new\\|^- fix\\|^- bug\\|^- hotfix' || true",
                        returnStdout: true
                    ).trim()

                    def notes = """# Release ${version}
**Date:** ${new Date().format('yyyy-MM-dd')}
**Build:** #${env.BUILD_NUMBER}
**Branch:** ${env.BRANCH_NAME}

## Features
${features ?: '_No new features_'}

## Bug Fixes
${fixes ?: '_No bug fixes_'}

## Other Changes
${other ?: '_No other changes_'}

## Docker Images
${['auth','identity','promotion','notification','form','file','gateway','dashboard'].collect { "- ${env.IMAGE_PREFIX}/${it}-service:${env.IMAGE_TAG}" }.join('\n')}
"""
                    writeFile file: "RELEASE_NOTES_${version}.md", text: notes
                    archiveArtifacts artifacts: "RELEASE_NOTES_${version}.md"

                    sh "git tag -a ${version} -m 'Release ${version}'"
                    sh "git push origin ${version}"
                }
            }
        }
    }

    post {
        failure {
            echo "Pipeline FAILED on branch ${env.BRANCH_NAME} — build #${env.BUILD_NUMBER}"
        }
        success {
            echo "Pipeline PASSED on branch ${env.BRANCH_NAME} — build #${env.BUILD_NUMBER}"
        }
    }
}
