package com.xiongbeer.webveins;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.xiongbeer.webveins.check.SelfTest;
import com.xiongbeer.webveins.saver.HDFSManager;
import com.xiongbeer.webveins.service.api.APIServer;
import com.xiongbeer.webveins.utils.IdProvider;
import com.xiongbeer.webveins.utils.InitLogger;

import org.apache.curator.framework.CuratorFramework;

import com.xiongbeer.webveins.service.local.Server;
import com.xiongbeer.webveins.zk.worker.Worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.misc.Signal;
import sun.misc.SignalHandler;

@SuppressWarnings("restriction")
public class WebVeinsServer {
    private static WebVeinsServer wvServer;
	private Server server;
	private Worker worker;
	private String serverId;
	private CuratorFramework client;
	private APIServer apiServer;
	private HDFSManager hdfsManager;
    private Logger logger = LoggerFactory.getLogger(WebVeinsServer.class);
    private ExecutorService serviceThreadPool = Executors.newFixedThreadPool(2);

	private WebVeinsServer() throws IOException {
    	Configuration.getInstance();
        client = SelfTest.checkAndGetZK();
        if(client == null){
            logger.error("[init] Connect to zookeeper server failed.");
            System.exit(1);
        }
        hdfsManager = SelfTest.checkAndGetHDFS();
        if(hdfsManager == null){
            logger.error("[init] Connect to hdfs failed.");
            System.exit(1);
        }
        apiServer = new APIServer(client, hdfsManager);
        serverId = new IdProvider().getIp();
        /* 监听kill信号 */
        SignalHandler handler = new StopSignalHandler();
        Signal termSignal = new Signal("TERM");
        Signal.handle(termSignal, handler);
    }
    
    public static synchronized WebVeinsServer getInstance()
            throws IOException {
        if(wvServer == null){
        	wvServer = new WebVeinsServer();
        }
        return wvServer;
    }

    public void runServer() throws IOException {
        worker = new Worker(client, serverId);
        server = new Server(Configuration.LOCAL_PORT, worker);
        server.bind();
    }

    public void run(){
        /* 主服务 */
        serviceThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    logger.info("run local server");
                    wvServer.runServer();
                } catch (IOException e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        });

        /* 本地命令行服务 */
        serviceThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                apiServer.run(Configuration.LOCAL_SHELL_PORT);
            }
        });
    }

    @SuppressWarnings("restriction")
    private class StopSignalHandler implements SignalHandler {
        @Override
        public void handle(Signal signal) {
            try {
                logger.info("stoping server...");
                server.stop();
                logger.info("stoping api service...");
                client.close();
                apiServer.stop();
                hdfsManager.close();
            } catch (Throwable e) {
                System.out.println("handle|Signal handler" + "failed, reason "
                        + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args)
            throws IOException, InterruptedException {
        if(SelfTest.checkRunning(WebVeinsServer.class.getSimpleName())){
            System.out.println("[Error] Service has already running");
            System.exit(1);
        }
        InitLogger.init();
        WebVeinsServer server = WebVeinsServer.getInstance();
        server.run();
    }
}
