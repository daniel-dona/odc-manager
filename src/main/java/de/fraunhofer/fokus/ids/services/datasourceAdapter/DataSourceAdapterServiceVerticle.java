package de.fraunhofer.fokus.ids.services.datasourceAdapter;

import de.fraunhofer.fokus.ids.models.Constants;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.client.WebClient;
import io.vertx.serviceproxy.ServiceBinder;
/**
 * @author Vincent Bohlen, vincent.bohlen@fokus.fraunhofer.de
 */
public class DataSourceAdapterServiceVerticle extends AbstractVerticle {

    private Logger LOGGER = LoggerFactory.getLogger(DataSourceAdapterServiceVerticle.class.getName());

    @Override
    public void start(Future<Void> startFuture) {
        WebClient webClient = WebClient.create(vertx);

        ConfigStoreOptions confStore = new ConfigStoreOptions()
                .setType("env");

        ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(confStore);

        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

        retriever.getConfig(ar -> {
            if (ar.succeeded()) {
                DataSourceAdapterService.create(vertx, webClient, ar.result().getInteger("CONFIG_MANAGER_PORT"), ar.result().getString("CONFIG_MANAGER_HOST"), ar.result().getString("REPOSITORY"),  ready -> {
                    if (ready.succeeded()) {
                        ServiceBinder binder = new ServiceBinder(vertx);
                        binder
                                .setAddress(Constants.DATASOURCEADAPTER_SERVICE)
                                .register(DataSourceAdapterService.class, ready.result());
                        LOGGER.info("Datasourceadapterservice successfully started.");
                        startFuture.complete();
                    } else {
                        LOGGER.error(ready.cause());
                        startFuture.fail(ready.cause());
                    }
                });
            } else {
                LOGGER.error(ar.cause());
                startFuture.fail(ar.cause());
            }
        });
    }
}
