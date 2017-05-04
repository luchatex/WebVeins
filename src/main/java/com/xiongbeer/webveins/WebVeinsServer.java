package com.xiongbeer.webveins;

import java.io.IOException;
import java.util.*;

import com.google.protobuf.InvalidProtocolBufferException;
import com.xiongbeer.webveins.service.BalanceClient;
import com.xiongbeer.webveins.utils.IdProvider;
import com.xiongbeer.webveins.utils.InitLogger;
import com.xiongbeer.webveins.zk.manager.ManagerData;
import org.apache.zookeeper.AsyncCallback.VoidCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

import com.xiongbeer.webveins.service.Server;
import com.xiongbeer.webveins.zk.worker.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebVeinsServer implements Watcher {
	private Server server;
	private Worker worker;
	private String serverId;
	private ZooKeeper zk;
	private Configuration configuration;
	private static WebVeinsServer wvServer;
	private BalanceClient balanceClient;
    private Logger logger = LoggerFactory.getLogger(WebVeinsServer.class);
	private WebVeinsServer() throws IOException {
    	configuration = Configuration.getInstance();
        zk = new ZooKeeper(Configuration.INIT_SERVER, 1000, this);
        serverId = new IdProvider().getIp();
        balanceClient = new BalanceClient();
    }
    
    public static synchronized WebVeinsServer getInstance() throws IOException {
        if(wvServer == null){
        	wvServer = new WebVeinsServer();
        }
        return wvServer;
    }
    
    public void stopServer(){
        server.stop();
    }
    
    public void setZK(ZooKeeper zk){
        this.zk = zk;
    }

    public void runServer() throws IOException {
        worker = new Worker(zk, serverId);
        server = new Server(Configuration.LOCAL_HOST,
                Configuration.LOCAL_PORT, worker.getTaskWorker());
        server.bind();
    }

    private VoidCallback connectManagerCallback = new VoidCallback() {
        @Override
        public void processResult(int rc, String path, Object ctx) {
            switch (KeeperException.Code.get(rc)) {
                case CONNECTIONLOSS:
                    connectManager();
                    break;
                case OK:
                    try {
                        connectBalanceManager();
                    } catch (KeeperException.ConnectionLossException e) {
                        connectManager();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (KeeperException e) {
                        e.printStackTrace();
                    }
                    break;
                default:

                    System.exit(1);
                    break;
            }
        }
    };

    /**
     * 客户端到连接到一个负载最小的manager端
     *
     * sync表示拿到目前最新的信息
     */
    public void connectManager(){
        zk.sync(ZnodeInfo.MANAGERS_PATH, connectManagerCallback, null);
    }

    public void connectBalanceManager() throws KeeperException, InterruptedException {
        ArrayList<String> children =
                (ArrayList<String>) zk.getChildren(ZnodeInfo.MANAGERS_PATH, false);
        List<ManagerData> managerData = new LinkedList<ManagerData>();
        for(String child:children){
            byte[] data = zk.getData(ZnodeInfo.MANAGERS_PATH + '/' + child,
                    false, null);
            try {
                managerData.add(new ManagerData(data));
            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }
        }
        Collections.sort(managerData);

        /* 拿到负载最小的Manager */
        ManagerData manager = managerData.get(0);
        balanceClient.connect(manager, this);
    }

    @Override
	public void process(WatchedEvent arg0) {}
    
    public static void main(String[] args) throws IOException {
        InitLogger.init();
        WebVeinsServer server = WebVeinsServer.getInstance();
        try {
            server.connectBalanceManager();
        } catch (KeeperException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
