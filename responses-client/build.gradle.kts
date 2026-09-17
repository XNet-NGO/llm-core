plugins { `maven-publish` }

kotlin {
    jvm()
    sourceSets { commonMain { dependencies { api(projects.responsesClient.responsesClientCore) } } }
}