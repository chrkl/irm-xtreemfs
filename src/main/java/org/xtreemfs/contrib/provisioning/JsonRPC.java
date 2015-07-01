/**
 * Copyright 2011-2012 Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 * Authors: Michael Berlin, Patrick Schäfer
 */
package org.xtreemfs.contrib.provisioning;

import com.goebl.david.Webb;
import com.google.gson.Gson;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Error;
import com.thetransactioncompany.jsonrpc2.JSONRPC2ParseException;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Request;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;
import com.thetransactioncompany.jsonrpc2.server.Dispatcher;
import com.thetransactioncompany.jsonrpc2.server.MessageContext;
import net.minidev.json.JSONObject;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.xtreemfs.common.libxtreemfs.Client;
import org.xtreemfs.common.libxtreemfs.ClientFactory;
import org.xtreemfs.common.libxtreemfs.Options;
import org.xtreemfs.contrib.provisioning.LibJSON.*;
import org.xtreemfs.foundation.SSLOptions;
import org.xtreemfs.foundation.json.JSONParser;
import org.xtreemfs.foundation.json.JSONString;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Controller
public class JsonRPC implements ResourceLoaderAware {

    private static final int DEFAULT_DIR_PORT = 32638;

    protected static final String OSD_SELECTION_POLICY = "xtreemfs.osel_policy";
    protected static final String REPLICA_SELECTION_POLICY = "xtreemfs.rsel_policy";
    protected String DEFAULT_DIR_CONFIG = "default_dir";
    protected static final String SECONDS_SINCE_LAST_UPDATE = "seconds_since_last_update";

    protected Client client = null;

    protected Dispatcher dispatcher = null;
    protected InetSocketAddress[] dirAddresses = null;
    protected InetSocketAddress schedulerAddress = null;

    protected int dir_port = DEFAULT_DIR_PORT;
    protected String adminPassword = "";

    protected ResourceLoader resourceLoader;

    protected SSLOptions sslOptions = null;

    protected IRMConfig config = null;

    protected Gson gson = new Gson();

    enum METHOD {
      getResources,
     
      getAllocSpec,
        
      computeCapacity,

      createReservation,

      checkReservation,
      
      releaseReservation,

      releaseAllReservations,

      getMetrics,

      // not needed??      
        calculateResourceAgg,
        listReservations,
    }

    public JsonRPC(String defaultDirConfigFile) {
        this.DEFAULT_DIR_CONFIG = defaultDirConfigFile;
    }

    public JsonRPC(int defaultPort) {
        this.dir_port = defaultPort;
    }

    public JsonRPC() {
        this.dir_port = DEFAULT_DIR_PORT;
    }

    public void setResourceLoader(ResourceLoader arg) {
        this.resourceLoader = arg;
    }

    public File getResource(String location) throws IOException{
        if (this.resourceLoader!=null) {
            return this.resourceLoader.getResource(location).getFile();
        }
        return null;
    }

    @PostConstruct
    public void init() throws FileNotFoundException, IOException {

        // Start Xtreemfs
        Logging.start(Logging.LEVEL_DEBUG, Category.tool);

        // If /etc/xos/xtreemfs/default_dir exists, get DIR address from it.
        File defaultIrmConfigFile = new File("/xtreemfs_data/irm.properties");
        if(!defaultIrmConfigFile.exists()) {
            defaultIrmConfigFile = getResource("WEB-INF/" + this.DEFAULT_DIR_CONFIG);
            if (defaultIrmConfigFile == null) { // for j-unit tests check this path too
                defaultIrmConfigFile = new File("src/main/webapp/WEB-INF/" + this.DEFAULT_DIR_CONFIG);
            }
        }

        if (defaultIrmConfigFile != null && defaultIrmConfigFile.exists()) {
            Logger.getLogger(JsonRPC.class.getName()).log(Level.INFO, "Found a config file: " + defaultIrmConfigFile.getAbsolutePath());
            // the DIRConfig does not contain a "dir_service." property
            config = new IRMConfig(defaultIrmConfigFile.getAbsolutePath());
            config.read();
            this.dirAddresses = config.getDirectoryServices();
            this.schedulerAddress = config.getSchedulerService();
            this.adminPassword = config.getAdminPassword();
        } else {
            Logger.getLogger(JsonRPC.class.getName()).log(Level.INFO, "No config file found!");
            this.dirAddresses = new InetSocketAddress[]{new InetSocketAddress("localhost", this.dir_port)};
        }

        if (this.adminPassword == null) {
            this.adminPassword = "";
        }

        if(config != null) {
            LibJSON.setIrmConfig(config);
        }

        LibJSON.setCapacityMonitor(new CapacityMonitor());

        Logger.getLogger(JsonRPC.class.getName()).log(Level.INFO, "Connecting to DIR-Address: " + this.dirAddresses[0].getAddress().getCanonicalHostName());

        try {
            Options options = new Options();

            // SSL options
            if (config != null && config.isUsingSSL()) {
                final boolean gridSSL = true;
                this.sslOptions = new SSLOptions(
                        new FileInputStream(
                                config.getServiceCredsFile()), config.getServiceCredsPassphrase(), config.getServiceCredsContainer(),
                        null, null, "none", false, gridSSL, null);
            }

            String[] dirAddressesString = LibJSON.generateDirAddresses(dirAddresses);

            this.client = ClientFactory.createClient(dirAddressesString, AbstractRequestHandler.getGroups(), this.sslOptions, options);
            this.client.start();

            // Create a new JSON-RPC 2.0 request dispatcher
            // Register the Volume, OSP/RSP, ACL, etc. handlers
            this.dispatcher =  new Dispatcher();

            // Volume handlers
            this.dispatcher.register(new ListReservationsHandler(this.client));
            this.dispatcher.register(new CreateReservationHandler(this.client));
            this.dispatcher.register(new ReleaseReservationHandler(this.client));
            this.dispatcher.register(new CheckReservation(this.client));
            this.dispatcher.register(new AvailableResources(this.client));

            this.dispatcher.register(new AllocSpecHandler(this.client));
            this.dispatcher.register(new ComputeCapacityHandler(this.client));
            this.dispatcher.register(new CalculateResourceAggHandler(this.client));
            this.dispatcher.register(new ReleaseAllReservationsHandler((this.client)));
            this.dispatcher.register(new MetricsHandler((this.client)));

        } catch (Exception e) {
            e.printStackTrace();

            // stop client
            stop();

            throw new IOException("client.start() failed (threw an exception)");
        }

        if(config.getCrsUrl() != null && !config.getCrsUrl().equals("")) {
            addMangerToCrs(config.getCrsUrl());
        }
    }

    @PreDestroy
    public void stop() {
        if (this.client != null) {
            this.client.shutdown();
            this.client = null;
        }
    }

    @RequestMapping(
            value = "/executeMethod",
            method = RequestMethod.POST,
            produces = "application/json")
    public String execute(String json_string) {
        JSONRPC2Response resp = null;
        try {
            Logger.getLogger(JsonRPC.class.getName()).log(
                    Level.FINE, "received request for method: " + json_string) ;

            JSONRPC2Request req = JSONRPC2Request.parse(json_string, true, true);

            Logger.getLogger(JsonRPC.class.getName()).log(
                    Level.FINE, "received request for method: " + req.getMethod() + " params: " + req.getParams());

            resp = this.dispatcher.process(req, null);

        } catch (JSONRPC2ParseException e) {
            Logger.getLogger(JsonRPC.class.getName()).log(Level.WARNING, null, e);
            resp = new JSONRPC2Response(new JSONRPC2Error(JSONRPC2Error.PARSE_ERROR.getCode(), e.getMessage()), 0);
        }
        // Write JSON response
        return resp.toJSONString();
    }

    /**
     * Implements a handler for the
     */
    public class CalculateResourceAggHandler extends AbstractRequestHandler {

        public CalculateResourceAggHandler(Client c) {
            super(c, new METHOD[]{METHOD.calculateResourceAgg});
        }

        // Processes the requests
        @SuppressWarnings("unchecked")
        @Override
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {

            Resources res = gson.fromJson(JSONObject.toJSONString((Map<String, ?>)req.getParams()), Resources.class);
            Resource resource = LibJSON.calculateResourceAgg(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    getGroups(),
                    getAuth(JsonRPC.this.adminPassword),
                    client);

            JSONString json = new JSONString(gson.toJson(resource));
            return new JSONRPC2Response(JSONParser.parseJSON(json), req.getID());
        }
    }


    /**
     * Implements a handler for the
     */
    public class ComputeCapacityHandler extends AbstractRequestHandler {

        public ComputeCapacityHandler(Client c) {
            super(c, new METHOD[]{METHOD.computeCapacity});
        }

        // Processes the requests
        @SuppressWarnings("unchecked")
        @Override
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {

            ResourceCapacity res = gson.fromJson(JSONObject.toJSONString((Map<String, ?>)req.getParams()), ResourceCapacity.class);

            Map<String, Resource> resource = LibJSON.computeCapacity(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    getGroups(),
                    getAuth(JsonRPC.this.adminPassword),
                    client);

            JSONString json = new JSONString(gson.toJson(resource));
            return new JSONRPC2Response(JSONParser.parseJSON(json), req.getID());
        }
    }

    /**
     * Implements a handler for the 
     */
    public class MetricsHandler extends AbstractRequestHandler {

        public MetricsHandler(Client c) {
            super(c, new METHOD[]{METHOD.getMetrics});
        }

        // Processes the requests
        @Override
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {

          MetricReq res = gson.fromJson(JSONObject.toJSONString((Map<String, ?>)req.getParams()), MetricReq.class);
          
          MetricResp resource = LibJSON.getMetrics(
              res,
              LibJSON.generateSchedulerAddress(schedulerAddress),
              getGroups(),
              getAuth(JsonRPC.this.adminPassword),
              client);
          
          JSONString json = new JSONString(gson.toJson(resource));
          return new JSONRPC2Response(JSONParser.parseJSON(json), req.getID());
        }
    }

    /**
     * Implements a handler for the 
     */
    public class AllocSpecHandler extends AbstractRequestHandler {

        public AllocSpecHandler(Client c) {
            super(c, new METHOD[]{METHOD.getAllocSpec});
        }

        // Processes the requests
        @Override
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {
            Types resource = LibJSON.getAllocSpec();
            JSONString json = new JSONString(gson.toJson(resource));
            return new JSONRPC2Response(JSONParser.parseJSON(json), req.getID());
        }
    }

    /**
     * Implements a handler for the
     */
    public class CreateReservationHandler extends AbstractRequestHandler {

        public CreateReservationHandler(Client c) {
            super(c, new METHOD[]{METHOD.createReservation});
        }

        // Processes the requests
        @SuppressWarnings("unchecked")
        @Override
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {

            Allocation res = gson.fromJson(JSONObject.toJSONString((Map<String, ?>)req.getParams()), Allocation.class);

            ReservationID reservations = LibJSON.createReservation(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    dirAddresses,
                    sslOptions,
                    getGroups(),
                    getAuth(JsonRPC.this.adminPassword),
                    client);

            JSONString json = new JSONString(gson.toJson(reservations));
            return new JSONRPC2Response(JSONParser.parseJSON(json), req.getID());
        }
    }



    /**
     * Implements a handler for the "releaseResources" JSON-RPC method
     */
    public class ReleaseReservationHandler extends AbstractRequestHandler {

        public ReleaseReservationHandler(Client c) {
            super(c, new METHOD[]{METHOD.releaseReservation});
        }

        // Processes the requests
        @Override
        @SuppressWarnings("unchecked")
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {
            ReservationID res = gson.fromJson(JSONObject.toJSONString((Map<String, ?>)req.getParams()), ReservationID.class);

            LibJSON.releaseReservation(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    getGroups(),
                    getAuth(JsonRPC.this.adminPassword),
                    client
            );

            return new JSONRPC2Response("", req.getID());
        }
    }

    /**
     * Implements a handler for the "checkReservation" JSON-RPC method
     */
    public class CheckReservation extends AbstractRequestHandler {

        public CheckReservation(Client c) {
            super(c, new METHOD[]{METHOD.checkReservation});
        }

        // Processes the requests
        @Override
        @SuppressWarnings("unchecked")
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {
            ReservationID res = gson.fromJson(JSONObject.toJSONString((Map<String, ?>)req.getParams()), ReservationID.class);

            ReservationStati reservStat = LibJSON.checkReservation(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    dirAddresses,
                    sslOptions,
                    getGroups(),
                    getAuth(JsonRPC.this.adminPassword),
                    this.client);

            JSONString json = new JSONString(gson.toJson(reservStat));
            return new JSONRPC2Response(JSONParser.parseJSON(json), req.getID());
        }
    }

    /**
     * Implements a handler for the
     *    "listReservations"
     * JSON-RPC method
     */
    public class ListReservationsHandler extends AbstractRequestHandler {

        public ListReservationsHandler(Client c) {
            super(c, new METHOD[]{METHOD.listReservations});
        }

        // Processes the requests
        @Override
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {
            Addresses addresses = LibJSON.listReservations(dirAddresses, client);

            JSONString json = new JSONString(gson.toJson(addresses));
            return new JSONRPC2Response(JSONParser.parseJSON(json), req.getID());
        }
    }

    /**
     *  Implements a handler for the
     *    "getResources"
     *  JSON-RPC method
     */
    public class AvailableResources extends AbstractRequestHandler {

        public AvailableResources(Client c) {
            super(c, new METHOD[]{METHOD.getResources});
        }

        // Processes the requests
        @Override
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {

            Resources res = LibJSON.getResources(
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    dirAddresses,
                    getGroups(),
                    getAuth(adminPassword),
                    client);

            JSONString json = new JSONString(gson.toJson(res));

            return new JSONRPC2Response(JSONParser.parseJSON(json), req.getID());
        }

    }

    public class ReleaseAllReservationsHandler extends AbstractRequestHandler {

        public ReleaseAllReservationsHandler(Client c) {
            super(c, new METHOD[]{METHOD.releaseAllReservations});
        }

        @Override
        public JSONRPC2Response doProcess(JSONRPC2Request req, MessageContext ctx) throws Exception {
            LibJSON.releaseAllReservations(
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    dirAddresses,
                    getGroups(),
                    getAuth(adminPassword),
                    client);
            return new JSONRPC2Response(null);
        }
    }

    private void addMangerToCrs(String crsUrl) {
        for(int errorCount = 0; errorCount < 15; errorCount++) {
            try {
                tryAddManagerToCrs(crsUrl);
                break;
            } catch (Exception e) {
                Logger.getLogger(JsonRPC.class.getName()).log(Level.WARNING, "Adding manager to CRS failed: " + e.getMessage());
                try{
                    Thread.sleep(10000);
                } catch (InterruptedException ex){
                    break;
                }
            }
        }
    }

    private void tryAddManagerToCrs(String crsUrl) throws Exception {
        String url = getAddManagerURL(crsUrl);
        Webb webb = Webb.create();
        com.goebl.david.Response response = webb.post(url).header("Content-Type", "application/json")
                .body("{\"Address\": \"" + LibJSON.getMyAddress() + "\", \"Port\": \"8080\", \"Name\": \"IRM-XtreemFS\"}")
                .asJsonObject();
        response.getBody();
    }

    private String getAddManagerURL(String crsUrl) {
        if(crsUrl.endsWith("/addManager")) {
            return crsUrl;
        } else if (crsUrl.endsWith("/")) {
            return crsUrl + "addManager";
        } else {
            return crsUrl + "/addManager";
        }
    }
}
