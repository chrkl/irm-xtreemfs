/*
 * Copyright (c) 2008-2015 by Christoph Kleineweber,
 *               Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.contrib.provisioning;

import org.xtreemfs.common.libxtreemfs.Client;
import org.xtreemfs.common.libxtreemfs.Volume;
import org.xtreemfs.foundation.SSLOptions;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC;
import org.xtreemfs.pbrpc.generatedinterfaces.MRC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author Christoph Kleineweber <kleineweber@zib.de>
 */
public class CapacityMonitor {
    private ConcurrentMap<String, MonitoringInfo> volumes;
    Thread monitoringThread;

    public CapacityMonitor() {
        this.volumes = new ConcurrentHashMap<String, MonitoringInfo>();

        this.monitoringThread = new Thread(new Runnable() {
            public void run() {
                while(true) {
                    try {
                        for(MonitoringInfo monitoringInfo: volumes.values()) {
                            long timeStamp = System.currentTimeMillis();
                            if(monitoringInfo.lastCheck + monitoringInfo.pollInterval * 1000 <= timeStamp) {
                                monitoringInfo.lastCheck = timeStamp;
                                Volume volume = LibJSON.openVolume(monitoringInfo.volumeName,
                                        monitoringInfo.sslOptions,
                                        monitoringInfo.client);
                                String usedCapacityStr = volume.getXAttr(monitoringInfo.uc, "/", "xtreemfs.used_space");
                                long usedCapacity = Long.valueOf(usedCapacityStr);
                                monitoringInfo.usedCapacity.add(new MonitoringElement(timeStamp / 1000, usedCapacity));
                                volume.close();
                            }
                        }

                        Thread.sleep(1000);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        });
        this.monitoringThread.start();
    }

    public void addVolume(Client client,
                          SSLOptions sslOptions,
                          RPC.UserCredentials uc,
                          RPC.Auth auth,String volumeName,
                          int pollInterval,
                          long reservedCapacity) {
        this.volumes.putIfAbsent(volumeName, new MonitoringInfo(client, sslOptions, uc, auth, volumeName, pollInterval,
                reservedCapacity));
    }

    public void removeVolume(String volumeName) {
        this.volumes.remove(volumeName);
    }

    public String getCapacityUtilization(String volumeName, int entry) {
        String result = "";
        if (volumes.containsKey(volumeName)) {
            MonitoringInfo monitoringInfo = volumes.get(volumeName);
            List<MonitoringElement> elements = monitoringInfo.usedCapacity;
            int listBegin = (entry < 0)?(elements.size() + entry):Math.max(entry-1,0);

            for (int i = listBegin; i < elements.size() ; i++) {
                MonitoringElement element = elements.get(i);
                result += (i+1) + "," + element.timeStamp + ","
                        + Math.min(100, element.capacity * 100 / monitoringInfo.reservedCapacity) + "\n";
            }
        }
        return result;
    }

    private class MonitoringInfo {
        String volumeName;
        int pollInterval;
        List<MonitoringElement> usedCapacity;
        long lastCheck;
        Client client;
        SSLOptions sslOptions;
        RPC.UserCredentials uc;
        RPC.Auth auth;
        long reservedCapacity;

        public MonitoringInfo(Client client,
                              SSLOptions sslOptions,
                              RPC.UserCredentials uc,
                              RPC.Auth auth,
                              String volumeName,
                              int pollInterval,
                              long reservedCapacity) {
            this.client = client;
            this.sslOptions = sslOptions;
            this.uc = uc;
            this.auth = auth;
            this.volumeName = volumeName;
            this.pollInterval = pollInterval;
            this.usedCapacity = new ArrayList<MonitoringElement>();
            this.lastCheck = 0L;
            this.reservedCapacity = reservedCapacity;
        }
    }

    private class MonitoringElement {
        long timeStamp;
        long capacity;

        public MonitoringElement(long timeStamp, long capacity) {
            this.timeStamp = timeStamp;
            this.capacity = capacity;
        }
    }
}
