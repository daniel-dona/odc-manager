package de.fraunhofer.fokus.ids.main;

import de.fraunhofer.fokus.ids.controllers.*;
import de.fraunhofer.fokus.ids.models.DataAssetDescription;
import de.fraunhofer.fokus.ids.persistence.entities.DataSource;
import de.fraunhofer.fokus.ids.persistence.managers.AuthManager;
import de.fraunhofer.fokus.ids.persistence.managers.BrokerManager;
import de.fraunhofer.fokus.ids.persistence.util.DatabaseConnector;
import de.fraunhofer.fokus.ids.services.ConfigService;
import de.fraunhofer.fokus.ids.services.InitService;
import de.fraunhofer.fokus.ids.services.authAdapter.AuthAdapterServiceVerticle;
import de.fraunhofer.fokus.ids.services.brokerService.BrokerServiceVerticle;
import de.fraunhofer.fokus.ids.services.datasourceAdapter.DataSourceAdapterServiceVerticle;
import de.fraunhofer.fokus.ids.services.dockerService.DockerServiceVerticle;
import de.fraunhofer.fokus.ids.utils.IDSMessageParser;
import de.fraunhofer.iais.eis.ArtifactRequestMessage;
import de.fraunhofer.iais.eis.DescriptionRequestMessage;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;

import java.io.*;
import java.util.*;

/**
 * @author Vincent Bohlen, vincent.bohlen@fokus.fraunhofer.de
 */
public class MainVerticle extends AbstractVerticle{
	private Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class.getName());
	private Router router;
	private AuthManager authManager;
	private ConnectorController connectorController;
	private DataAssetController dataAssetController;
	private DataSourceController dataSourceController;
	private JobController jobController;
	private BrokerController brokerController;
	private BrokerManager brokerManager;
	private ConfigService configService;
	private int servicePort;
	private DockerController dockerController;
	private FileUploadController fileUploadController;

	@Override
	public void start(Promise<Void> startPromise) {
		this.authManager = new AuthManager(vertx);
		this.connectorController = new ConnectorController(vertx);
		this.dataAssetController = new DataAssetController(vertx);
		this.dataSourceController = new DataSourceController(vertx);
		this.fileUploadController = new FileUploadController(vertx);
		this.jobController = new JobController(vertx);
		this.brokerController = new BrokerController(vertx);
		this.brokerManager = new BrokerManager();
		this.configService = new ConfigService(vertx);
		this.dockerController = new DockerController(vertx);

		DeploymentOptions deploymentOptions = new DeploymentOptions();
		deploymentOptions.setWorker(true);

		LOGGER.info("Starting services...");
		Future<String> deployment = Future.succeededFuture();
		deployment
				.compose(id2 -> {
					Promise<String> datasourceAdapterPromise = Promise.promise();
					Future<String> datasourceAdapterFuture = datasourceAdapterPromise.future();
					vertx.deployVerticle(DataSourceAdapterServiceVerticle.class.getName(), deploymentOptions, datasourceAdapterFuture);
					return datasourceAdapterFuture;
				})
				.compose(id3 -> {
					Promise<String> dockerServiceAdapterPromise = Promise.promise();
					Future<String> dockerServiceAdapterFuture = dockerServiceAdapterPromise.future();
					vertx.deployVerticle(DockerServiceVerticle.class.getName(), deploymentOptions, dockerServiceAdapterFuture);
					return dockerServiceAdapterFuture;
				})
				.compose(i45 -> {
					Promise<String> authAdapterPromise = Promise.promise();
					Future<String> authAdapterFuture = authAdapterPromise.future();
					vertx.deployVerticle(AuthAdapterServiceVerticle.class.getName(), deploymentOptions, authAdapterFuture);
					return authAdapterFuture;
				})
				.compose(id5 -> {
					Promise<String> brokerServicePromise = Promise.promise();
					Future<String> brokerServiceFuture = brokerServicePromise.future();
					vertx.deployVerticle(BrokerServiceVerticle.class.getName(), deploymentOptions, brokerServiceFuture);
					return brokerServiceFuture;
				})
				.compose(id6 -> {
					Promise<String> envPromise = Promise.promise();
					ConfigStoreOptions confStore = new ConfigStoreOptions()
							.setType("env");
					ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(confStore);
					ConfigRetriever retriever = ConfigRetriever.create(vertx, options);
					retriever.getConfig(ar -> {
						if (ar.succeeded()) {
							servicePort = ar.result().getInteger("SERVICE_PORT");
							DatabaseConnector.getInstance().create(vertx, ar.result().getJsonObject("DB_CONFIG"), 5);
							envPromise.complete();
						} else {
							envPromise.fail(ar.cause());
						}
					});
					return envPromise.future();
				}).onComplete( ar -> {
					if (ar.succeeded()) {
						InitService initService = new InitService(vertx);
						initService.initDatabase(reply -> {
							if(reply.succeeded()){
								router = Router.router(vertx);
								createHttpServer(vertx);
								startPromise.complete();
							}
							else{
								LOGGER.error(reply.cause());
								startPromise.fail(ar.cause());
							}
						});
					} else {
						LOGGER.error(ar.cause());
						startPromise.fail(ar.cause());
					}
				});

	}

	private void createHttpServer(Vertx vertx) {
		HttpServer server = vertx.createHttpServer();

		Set<String> allowedHeaders = new HashSet<>();
		allowedHeaders.add("x-requested-with");
		allowedHeaders.add("Access-Control-Allow-Origin");
		allowedHeaders.add("Access-Control-Allow-Credentials");
		allowedHeaders.add("origin");
		allowedHeaders.add("authorization");
		allowedHeaders.add("Content-Type");
		allowedHeaders.add("accept");
		allowedHeaders.add("Access-Control-Allow-Headers");
		allowedHeaders.add("Access-Control-Allow-Methods");
		allowedHeaders.add("X-PINGARUNER");

		Set<HttpMethod> allowedMethods = new HashSet<>();
		allowedMethods.add(HttpMethod.GET);
		allowedMethods.add(HttpMethod.POST);
		allowedMethods.add(HttpMethod.OPTIONS);

		router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders).allowedMethods(allowedMethods));
		router.route().handler(BodyHandler.create());

		router.post("/login").handler(routingContext ->
				authManager.login(routingContext.getBodyAsJson(), reply -> {
					if(reply.succeeded()) {
						if (reply.result() != null) {
							routingContext.response().end(reply.result());
						} else {
							routingContext.fail(401);
						}
					}
					else{
						routingContext.response().setStatusCode(404).end();
					}
				})
		);

		router.get("/about").handler(routingContext ->
				connectorController.about(result ->
						reply(result, routingContext.response())));

		router.post("/about")
				.handler(routingContext ->
				connectorController.checkMessage(IDSMessageParser.parse(routingContext.request().formAttributes()), DescriptionRequestMessage.class, result ->
						replyMessage(result, routingContext.response())));

		router.post("/infrastructure")
				.handler(routingContext -> {
				connectorController.routeMessage(IDSMessageParser.parse(routingContext.request().formAttributes()), result ->
						replyMessage(result, routingContext.response()));});


		router.get("/data/:id.:extension").handler(routingContext ->
				connectorController.payload(Long.parseLong(routingContext.request().getParam("id")), routingContext.request().getParam("extension"), result ->
						replyFile(result,Long.parseLong(routingContext.request().getParam("id")), routingContext.response())));

		router.get("/data/:id").handler(routingContext ->
				connectorController.payload(Long.parseLong(routingContext.request().getParam("id")), "", result ->
						replyFile(result,Long.parseLong(routingContext.request().getParam("id")), routingContext.response())));

		router.post("/data")
				.handler(routingContext ->
				connectorController.checkMessage(IDSMessageParser.parse(routingContext.request().formAttributes()), ArtifactRequestMessage.class, result ->
						replyMessage(result, routingContext.response())));

		router.route("/api/*").handler(JWTAuthHandler.create(authManager.getProvider()));

		router.route("/api/jobs/find/all").handler(routingContext ->
				jobController.findAll(result -> reply(result, routingContext.response())));

		router.route("/api/jobs/delete/all").handler(routingContext ->
				jobController.deleteAll(result -> reply(result, routingContext.response())));

		router.route("/api/dataassets/all/publish").handler(routingContext ->
				dataAssetController.publishAll(result -> reply(result, routingContext.response())));

		router.route("/api/dataassets/all/unpublish").handler(routingContext ->
				dataAssetController.unpublishAll(result -> reply(result, routingContext.response())));

		router.route("/api/dataassets/:id/publish").handler(routingContext ->
				dataAssetController.publish(Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/dataassets/:id/unpublish").handler(routingContext ->
				dataAssetController.unPublish(Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/dataassets/:id/delete").handler(routingContext ->
				dataAssetController.delete(Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/dataassets").handler(routingContext ->
				dataAssetController.index(result -> reply(result, routingContext.response())));

		router.route("/api/dataassets/counts").handler(routingContext ->
				dataAssetController.counts(result -> reply(result, routingContext.response())));


//		router.route("/dataassets/resource/:name").handler(routingContext ->
//				dataAssetController.resource("",result -> reply(result, routingContext.response())));

		router.post("/api/dataassets/add").handler(routingContext ->
						processDataAssetInformation(routingContext));

//		router.route("/uri/:name").handler(routingContext ->
//						getUri(result -> reply(result, routingContext.response()), routingContext)
//				);

		router.post("/api/datasources/add").handler(routingContext ->
				dataSourceController.add(toDataSource(routingContext.getBodyAsJson()), result -> reply(result, routingContext.response())));

		router.route("/api/datasources/delete/:id").handler(routingContext ->
				dataSourceController.delete(Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/datasources/findAll").handler(routingContext ->
				dataSourceController.findAllByType(result -> reply(result, routingContext.response())));

		router.route("/api/datasources/find/id/:id").handler(routingContext ->
				dataSourceController.findById(Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/datasources/find/type/:type").handler(routingContext ->
				dataSourceController.findByType(routingContext.request().getParam("type"), result -> reply(result, routingContext.response())));

		router.post("/api/datasources/edit/:id").handler(routingContext ->
				dataSourceController.update(toDataSource(routingContext.getBodyAsJson()),Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/datasources/schema/type/:type").handler(routingContext ->
				dataSourceController.getFormSchema(routingContext.request().getParam("type"), result -> reply(result, routingContext.response())));

		router.route("/api/datasources/schema/type/:type").handler(routingContext ->
				dataSourceController.getFormSchema(routingContext.request().getParam("type"), result -> reply(result, routingContext.response())));

		router.post("/api/broker/add").handler(routingContext ->
				brokerController.add(routingContext.getBodyAsJson().getString("url"), result -> reply(result, routingContext.response())));

		router.route("/api/broker/unregister/:id").handler(routingContext ->
				brokerController.unregister(Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/broker/register/:id").handler(routingContext ->
				brokerController.register(Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/broker/findAll").handler(routingContext ->
				brokerManager.findAll(result -> reply(result, routingContext.response())));

		router.route("/api/broker/delete/:id").handler(routingContext ->
				brokerController.delete(Long.parseLong(routingContext.request().getParam("id")), result -> reply(result, routingContext.response())));

		router.route("/api/configuration/get").handler(routingContext ->
				configService.getConfiguration(result -> reply(result, routingContext.response())));

		router.post("/api/configuration/edit").handler(routingContext ->
				configService.editConfiguration(routingContext.getBodyAsJson(), result -> reply(result, routingContext.response())));

		router.post("/api/upload/file").handler(routingContext ->
				fileUploadController.uploadFile(routingContext, result -> reply(result, routingContext.response())));

		router.route("/api/listAdapters").handler(routingContext->
				dataSourceController.listAdapters(result -> reply(result, routingContext.response())));

		router.route("/api/images").handler(routingContext->
				dockerController.getImages(result -> reply(result, routingContext.response())));

		router.post("/api/images/start").handler(routingContext ->  dockerController.startImages(routingContext.getBodyAsString(), reply -> reply(reply, routingContext.response())));
		router.post("/api/images/stop").handler(routingContext ->  dockerController.stopImages(routingContext.getBodyAsString(), reply -> reply(reply, routingContext.response())));

		server.requestHandler(router).listen(servicePort);
		LOGGER.info("odc-manager deployed on port "+servicePort);
	}

	private void processDataAssetInformation(RoutingContext routingContext){
		JsonObject jsonObject = routingContext.getBodyAsJson();
		String licenseurl = jsonObject.getString("licenseurl");
		String licensetitle = jsonObject.getString("licensetitle");
		jsonObject.remove("licenseurl");
		jsonObject.remove("licensetitle");
		dataAssetController.add(Json.decodeValue(jsonObject.toString(), DataAssetDescription.class), licenseurl, licensetitle, result -> reply(result, routingContext.response()));
	}

	//TODO: WORKAROUND. Find way to use Json.deserialize()
	private DataSource toDataSource(JsonObject bodyAsJson) {
		DataSource ds = new DataSource();
		ds.setData(bodyAsJson.getJsonObject("data"));
		ds.setDatasourceName(bodyAsJson.getString("datasourcename"));
		ds.setDatasourceType(bodyAsJson.getString("datasourcetype"));
		return ds;
	}

	private void reply(Object result, HttpServerResponse response) {
		if (result != null) {
			String entity = result.toString();
			response.putHeader("content-type", ContentType.APPLICATION_JSON.toString());
			response.end(entity);
		} else {
			response.setStatusCode(404).end();
		}
	}
	private void reply(AsyncResult result, HttpServerResponse response){
		if(result.succeeded()){
			reply(result.result(), response);
		}
		else{
			LOGGER.error("Result Future failed.",result.cause());
			response.setStatusCode(404).end();
		}
	}

	private void replyFile(AsyncResult<File> result,Long id, HttpServerResponse response){
		LOGGER.info("Sending file "+id.toString());
		dataAssetController.getFileName(id,stringAsyncResult->{
		    if (stringAsyncResult.succeeded()){
				LOGGER.info(stringAsyncResult.result().toString());
                if(result.succeeded()){
                    if(result.result() != null) {
						LOGGER.info("Sending file from "+stringAsyncResult.result().toString());
                        response.putHeader(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+stringAsyncResult.result());
                        response.sendFile(stringAsyncResult.result().toString());
                        new File(stringAsyncResult.result().toString()).delete();
                    } else {
						LOGGER.error("Dataset not found! ",stringAsyncResult.cause());
						response.setStatusCode(404).end();
					}
                }
                else{
                    LOGGER.error("Result Future failed.",result.cause());
                    response.setStatusCode(404).end();
                }
            }
		    else {
                LOGGER.error("Filename not found! ",stringAsyncResult.cause());
                response.setStatusCode(404).end();
            }
        });

	}

	private void replyMessage(AsyncResult<HttpEntity> result, HttpServerResponse response){
		if(result.succeeded()){
			if(result.result() != null) {
				try(ByteArrayOutputStream baos = new ByteArrayOutputStream()){
					Header contentTypeHeader =  result.result().getContentType();
					result.result().writeTo(baos);
					response.putHeader(contentTypeHeader.getName(), contentTypeHeader.getValue());
					response.end(Buffer.buffer(baos.toByteArray()));
				} catch (IOException e) {
					LOGGER.error(e);
					response.setStatusCode(500);
					response.end();
				}
			}
		}
		else{
			LOGGER.error("Result Future failed.",result.cause());
			response.setStatusCode(404).end();
		}
	}

	public static void main(String[] args) {
		String[] params = Arrays.copyOf(args, args.length + 1);
		params[params.length - 1] = MainVerticle.class.getName();
		Launcher.executeCommand("run", params);
	}
}
