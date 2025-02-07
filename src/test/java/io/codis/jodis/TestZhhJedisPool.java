/**
 * @(#)TestRoundRobinJedisPool.java, 2014-12-1. 
 * 
 * Copyright (c) 2014 CodisLabs.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.codis.jodis;

import static org.junit.Assert.assertEquals;

import java.io.Console;
import java.io.File;
import java.io.IOException;
// import java.net.ServerSocket;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.io.Closeables;

import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisException;

/**
 * @author Apache9
 */
public class TestZhhJedisPool {

    private ObjectMapper mapper = new ObjectMapper();

    private int zkPort=2181;

    private File testDir = new File("jodis.mango");

    private TestingServer zkServer;

    private int redisPort1;

    private RedisServer redis1;

    private int redisPort2;

    private RedisServer redis2;

    private Jedis jedis1;

    private Jedis jedis2;

    private String zkProxyDir = "/jodis.mango";

    private String zkProxyPath = "/codis3/jodis";

    // private String zkProxyDir = "/jodis/codis-zxf";

    private RoundRobinJedisPool jodisPool;

    // private static int probeFreePort() throws IOException {
    // try (ServerSocket ss = new ServerSocket(0)) {
    // ss.setReuseAddress(true);
    // return ss.getLocalPort();
    // }
    // }

    private static void waitUntilRedisStarted(int port) throws InterruptedException {
        for (;;) {
            try (Jedis jedis = new Jedis("127.0.0.1", port)) {
                if ("PONG".equals(jedis.ping())) {
                    break;
                }
            } catch (JedisException e) {
            }
            Thread.sleep(100);
        }
    }

    private void deleteDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }
        Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }

        });
    }

    private void addNode(String name, int port, String state)
            throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = new ZooKeeper("localhost:" + zkPort, 5000, null);
        try {            
            if (zk.exists(zkProxyDir, null) == null) {
                zk.create(zkProxyDir, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("addr", "127.0.0.1:" + port);
            node.put("state", state);
            zk.create(zkProxyDir + "/" + name, mapper.writer().writeValueAsBytes(node), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);
        } finally {
            zk.close();
        }
    }

    private void writePath()
            throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk = new ZooKeeper("192.168.4.77:" + zkPort, 5000, null);
        try {
            if (zk.exists(zkProxyPath, null) == null) {
                zk.create(zkProxyPath, null, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }
        } finally {
            zk.close();
        }
    }

    private void removeNode(String name) throws InterruptedException, KeeperException, IOException {
        ZooKeeper zk = new ZooKeeper("localhost:" + zkPort, 5000, null);
        try {
            zk.delete(zkProxyDir + "/" + name, -1);
        } finally {
            zk.close();
        }
    }

    @Before
    public void setUp() throws Exception {
        deleteDirectory(testDir);
        testDir.mkdirs();
        // zkServer = new TestingServer(-1, testDir, true);
        // zkPort = zkServer.getPort();
        zkServer = new TestingServer(20000, testDir, true);
        zkPort = zkServer.getPort();
        // redisPort1 = probeFreePort();
        redisPort1 = 20001;
        redis1 = new RedisServer(redisPort1);
        redis1.start();
        waitUntilRedisStarted(redisPort1);
        // redisPort2 = probeFreePort();
        redisPort2 = 20002;
        redis2 = new RedisServer(redisPort2);
        redis2.start();
        waitUntilRedisStarted(redisPort2);

        jedis1 = new Jedis("localhost", redisPort1);
        jedis2 = new Jedis("localhost", redisPort2);
        addNode("node1", redisPort1, "online");
        jodisPool = RoundRobinJedisPool.create().curatorClient("localhost:" + zkPort, 5000).zkProxyDir(zkProxyDir)
                .build();

        // // writePath();
        // jodisPool = RoundRobinJedisPool.create().curatorClient("192.168.4.77:" + zkPort, 5000).zkProxyDir(zkProxyDir)
        //       .build();
    }

    @After
    public void tearDown() throws IOException {
        Closeables.close(jodisPool, true);
        Closeables.close(jedis1, true);
        Closeables.close(jedis2, true);
        if (redis1 != null) {
            redis1.stop();
        }
        if (redis2 != null) {
            redis2.stop();
        }
        if (zkServer != null) {
            zkServer.stop();
        }
        deleteDirectory(testDir);
    }

    @Test
    public void test() throws IOException, InterruptedException, KeeperException {
        // try (Jedis jedis = jodisPool.getResource()) {
        //     jedis.select(5);
        //     jedis.set("k1", "v1");
        //     String value= jedis.get("k1");
        //     // jedis.del("k1");
        //     System.out.println("get-k1="+value);
        // }
        try (Jedis jedis = jodisPool.getResource()) {
        jedis.set("k1", "v1");
        }
        assertEquals("v1", jedis1.get("k1"));
        // // fake node
        // addNode("node2", 12345, "offline");
        // Thread.sleep(3000);
        // try (Jedis jedis = jodisPool.getResource()) {
        // jedis.set("k2", "v2");
        // }
        // assertEquals("v2", jedis1.get("k2"));

        // addNode("node3", redisPort2, "online");
        // Thread.sleep(3000);
        // try (Jedis jedis = jodisPool.getResource()) {
        // jedis.set("k3", "v3");
        // }
        // assertEquals("v3", jedis2.get("k3"));

        // removeNode("node1");
        // Thread.sleep(3000);
        // try (Jedis jedis = jodisPool.getResource()) {
        // jedis.set("k4", "v4");
        // }
        // assertEquals("v4", jedis2.get("k4"));
    }
}
