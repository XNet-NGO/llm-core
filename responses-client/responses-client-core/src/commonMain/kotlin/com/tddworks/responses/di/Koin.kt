package com.tddworks.responses.di

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.internal.AuthConfig
import com.tddworks.common.network.api.ktor.internal.ClientFeatures
import com.tddworks.common.network.api.ktor.internal.UrlBasedConnectionConfig
import com.tddworks.common.network.api.ktor.internal.createHttpClient
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.di.commonModule
import com.tddworks.di.createJson
import com.tddworks.responses.api.Responses
import com.tddworks.responses.api.ResponsesConfig
import com.tddworks.responses.api.internal.default
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

fun initResponses(config: ResponsesConfig, appDeclaration: KoinAppDeclaration = {}): Responses {
    return startKoin {
            appDeclaration()
            modules(commonModule(false) + responsesModules(config))
        }
        .koin
        .get<Responses>()
}

fun responsesModules(config: ResponsesConfig) = module {
    single<Responses> { Responses.default(config) }

    single<HttpRequester>(named("responsesHttpRequester")) {
        responsesHttpRequester(config)
    }
}

fun responsesHttpRequester(config: ResponsesConfig): HttpRequester =
    HttpRequester.default(
        createHttpClient(
            connectionConfig = UrlBasedConnectionConfig(config.baseUrl),
            authConfig = AuthConfig(config.apiKey),
            features = ClientFeatures(json = createJson()),
        ),
    )