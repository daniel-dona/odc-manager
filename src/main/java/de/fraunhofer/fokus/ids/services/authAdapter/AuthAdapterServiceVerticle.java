package de.fraunhofer.fokus.ids.services.authAdapter;

import de.fraunhofer.fokus.ids.models.Constants;
import de.fraunhofer.fokus.ids.utils.services.authService.AuthAdapterService;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.JksOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.serviceproxy.ServiceBinder;

import java.nio.file.Path;
import java.nio.file.Paths;
/**
 * @author Vincent Bohlen, vincent.bohlen@fokus.fraunhofer.de
 */
public class AuthAdapterServiceVerticle extends AbstractVerticle {

    private Logger LOGGER = LoggerFactory.getLogger(AuthAdapterServiceVerticle.class.getName());

    @Override
    public void start(Promise<Void> startPromise) {

        ConfigStoreOptions confStore = new ConfigStoreOptions()
                .setType("env");

        ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(confStore);

        ConfigRetriever retriever = ConfigRetriever.create(vertx, options);

        retriever.getConfig(ar -> {
            if (ar.succeeded()) {
                Path path = Paths.get("/ids/certs/");

                JksOptions jksOptions = new JksOptions();
                JsonObject authConfig = ar.result().getJsonObject("AUTH_CONFIG");
                Buffer store = vertx.fileSystem().readFileBlocking(path.resolve(authConfig.getString("truststorename")).toString());
                jksOptions.setValue(store).setPassword(authConfig.getString("keystorepassword"));
                WebClient webClient = WebClient.create(vertx, new WebClientOptions().setTrustStoreOptions(jksOptions));


                AuthAdapterService.create(vertx, webClient, path, authConfig, ready -> {
                    if (ready.succeeded()) {
                        ServiceBinder binder = new ServiceBinder(vertx);
                        binder
                                .setAddress(Constants.AUTHADAPTER_SERVICE)
                                .register(AuthAdapterService.class, ready.result());
                        LOGGER.info("AuthAdapterservice successfully started.");
                        startPromise.complete();
                    } else {
                        LOGGER.error(ready.cause());
                        startPromise.fail(ready.cause());
                    }
                });
            } else {
                LOGGER.error(ar.cause());
                startPromise.fail(ar.cause());
            }
        });
    }
}
