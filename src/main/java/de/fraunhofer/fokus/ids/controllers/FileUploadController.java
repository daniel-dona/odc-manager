package de.fraunhofer.fokus.ids.controllers;

import de.fraunhofer.fokus.ids.models.Constants;
import de.fraunhofer.fokus.ids.models.DataAssetDescription;
import de.fraunhofer.fokus.ids.persistence.entities.DataAsset;
import de.fraunhofer.fokus.ids.persistence.managers.DataAssetManager;
import de.fraunhofer.fokus.ids.persistence.managers.DataSourceManager;
import de.fraunhofer.fokus.ids.persistence.managers.JobManager;
import de.fraunhofer.fokus.ids.services.datasourceAdapter.DataSourceAdapterService;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
/**
 * @author Hoang Luan Ta, hoang.luan.ta@fokus.fraunhofer.de
 */
public class FileUploadController {
    private Logger LOGGER = LoggerFactory.getLogger(FileUploadController.class.getName());
    private DataAssetManager dataAssetManager;
    private DataSourceAdapterService dataSourceAdapterService;
    private DataSourceManager dataSourceManager;
    private JobManager jobManager;
    private BrokerController brokerController;
    private DataAssetController dataAssetController;

    public FileUploadController(Vertx vertx) {
        dataAssetManager = new DataAssetManager();
        jobManager = new JobManager();
        this.dataSourceManager = new DataSourceManager();
        dataSourceAdapterService = DataSourceAdapterService.createProxy(vertx, Constants.DATASOURCEADAPTER_SERVICE);
        brokerController = new BrokerController(vertx);
        dataAssetController = new DataAssetController(vertx);
    }

    private void addToZipFile(List<String> myList, ZipOutputStream zipOut) throws IOException {
        for (String filePath : myList){
            File input = new File(filePath.trim());
            FileInputStream fis = new FileInputStream(input);
            ZipEntry ze = new ZipEntry(input.getName());
            zipOut.putNextEntry(ze);
            byte[] tmp = new byte[4*1024];
            int size;
            while((size = fis.read(tmp)) != -1){
                zipOut.write(tmp, 0, size);
            }
            zipOut.flush();
            fis.close();
        }
        zipOut.close();

    }

    public void uploadFile (RoutingContext routingContext,Handler<AsyncResult<JsonObject>> resultHandler){
        DataAssetDescription dataAssetDescription = new DataAssetDescription();
        List<String> list= new ArrayList<>();
        for (FileUpload fileUpload : routingContext.fileUploads()){
            File fileSource = new File(fileUpload.uploadedFileName());
            File fileDes = new File("/file-uploads/"+fileUpload.fileName());
            try {
				LOGGER.info("Moving file to "+fileDes.getAbsolutePath());
                FileUtils.moveFile(fileSource,fileDes);
            } catch (IOException e) {
                e.printStackTrace();
            }
            list.add(fileUpload.fileName().trim());
        }
        JsonObject data = new JsonObject(routingContext.request().getFormAttribute("data"));
        LOGGER.info(data);
        Map<String, String> map = new HashMap<String , String>()
        {
            {
                put("datasettitle", data.getString("datasettitle"));
                put("datasetnotes", data.getString("datasetnotes"));
                put("file", list.get(0).toString());
            }
        };
        dataAssetDescription.setData(map);
        dataAssetDescription.setDatasourcetype("File Upload");
        dataAssetController.add(dataAssetDescription, data.getString("licenseurl"), data.getString("licensetitle"), resultHandler);

    }

    public void getFileUpload( Handler<AsyncResult<File>> resultHandler, DataAsset dataAsset)  {
        String getFiles = dataAsset.getUrl();
        String replace = getFiles.replace("[","");
        String replace1 = replace.replace("]","");
        List<String> myList = Arrays.asList(replace1.split(","));
        if (myList.size()>1){
            String tempName = "ids.zip";
            File file = new File(tempName);
            FileOutputStream fos ;
            ZipOutputStream zipOut ;
            try {
                fos = new FileOutputStream(tempName);
                zipOut = new ZipOutputStream(new BufferedOutputStream(fos));
                addToZipFile(myList,zipOut);
            } catch (IOException e) {
                e.printStackTrace();
            }
            resultHandler.handle(Future.succeededFuture(file));
        }else {
            String singleFile = myList.get(0);
            File file = new File(singleFile);
            File parent = new File(System.getProperty("java.io.tmpdir"));
            String baseName = FilenameUtils.getBaseName(singleFile);
            String extension = FilenameUtils.getExtension(singleFile);
            File temp = new File(parent, baseName+"."+extension);

            if (temp.exists()) {
                temp.delete();
            }

            try {
                temp.createNewFile();
                Files.copy(file.toPath(),temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            resultHandler.handle(Future.succeededFuture(temp));
        }
    }
}
