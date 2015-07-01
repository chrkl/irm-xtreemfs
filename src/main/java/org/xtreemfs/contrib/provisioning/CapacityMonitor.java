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
                                Volume volume = LibJSON.openVolume(monitoringInfo.volumeName,
                                        monitoringInfo.sslOptions,
                                        monitoringInfo.client);
                                MRC.StatVFS stat = volume.statFS(monitoringInfo.uc);
                                long usedCapacity = stat.getBlocks() * stat.getBsize();
                                monitoringInfo.usedCapacity.add(new MonitoringElement(timeStamp, usedCapacity));
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
                          int pollInterval) {
        this.volumes.putIfAbsent(volumeName, new MonitoringInfo(client, sslOptions, uc, auth, volumeName, pollInterval));
    }

    public void removeVolume(String volumeName) {
        this.volumes.remove(volumeName);
    }

    public String getCapacityUtilization(String volumeName, int begin, int end) {
        String result = "";
        if (volumes.containsKey(volumeName)) {
            List<MonitoringElement> elements = volumes.get(volumeName).usedCapacity;
            int listEnd = (end == -1)?elements.size() - 1:Math.min(end, elements.size() - 1);
            for (int i = begin; i <= listEnd; i++) {
                MonitoringElement element = elements.get(i);
                result += i + "," + element.timeStamp + "," + element.capacity + "\n";
            }
        }
        return result;
    }

    public long getAvgCapacityUtilization(String volumeName, int begin, int end) {
        long result = 0L;
        if (volumes.containsKey(volumeName)) {
            List<MonitoringElement> elements = volumes.get(volumeName).usedCapacity;
            int listEnd = (end == -1)?elements.size() - 1:Math.min(end, elements.size() - 1);
            long sum = 0L;
            for (int i = begin; i <= listEnd; i++) {
                MonitoringElement element = elements.get(i);
                sum += element.capacity;
            }
            result = (listEnd - begin > 0L) ? sum / (listEnd - begin) : 0L;
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

        public MonitoringInfo(Client client,
                              SSLOptions sslOptions,
                              RPC.UserCredentials uc,
                              RPC.Auth auth,
                              String volumeName,
                              int pollInterval) {
            this.client = client;
            this.sslOptions = sslOptions;
            this.uc = uc;
            this.auth = auth;
            this.volumeName = volumeName;
            this.pollInterval = pollInterval;
            this.usedCapacity = new ArrayList<MonitoringElement>();
            this.lastCheck = 0L;
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
