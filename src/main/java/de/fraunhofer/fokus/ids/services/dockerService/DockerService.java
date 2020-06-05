package de.fraunhofer.fokus.ids.services.dockerService;

import io.vertx.codegen.annotations.Fluent;
import io.vertx.codegen.annotations.GenIgnore;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;

@ProxyGen
@VertxGen
public interface DockerService {

    @Fluent
    DockerService getImages(Handler<AsyncResult<JsonArray>> resultHandler);

    @Fluent
    DockerService startImages(String uuid, Handler<AsyncResult<JsonObject>> resultHandler);

    @Fluent
    DockerService stopImages(String uuid, Handler<AsyncResult<JsonObject>> resultHandler);

    @Fluent
    DockerService listAdapters(Handler<AsyncResult<JsonArray>> resultHandler);

    @Fluent
    DockerService getDataAssetFormSchema(String dataSourceType, Handler<AsyncResult<JsonObject>> resultHandler);

    @Fluent
    DockerService getDataSourceFormSchema(String dataSourceType, Handler<AsyncResult<JsonObject>> resultHandler);

    @GenIgnore
    static DockerService create(WebClient webClient, int gatewayPort, String gatewayHost, String configManagerApikey, Handler<AsyncResult<DockerService>> readyHandler) {
        return new DockerServiceImpl(webClient, gatewayPort, gatewayHost, configManagerApikey, readyHandler);
    }

    @GenIgnore
    static DockerService createProxy(Vertx vertx, String address) {
        return new DockerServiceVertxEBProxy(vertx, address);
    }

}
