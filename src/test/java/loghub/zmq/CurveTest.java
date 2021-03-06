package loghub.zmq;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.zeromq.SocketType;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMonitor;
import org.zeromq.ZMonitor.ZEvent;

import fr.loghub.naclprovider.NaclCertificate;
import fr.loghub.naclprovider.NaclPrivateKeySpec;
import fr.loghub.naclprovider.NaclProvider;
import fr.loghub.naclprovider.NaclPublicKeySpec;
import loghub.LogUtils;
import loghub.ThreadBuilder;
import loghub.Tools;
import loghub.ZMQFactory;
import loghub.zmq.ZMQHelper.Method;
import loghub.zmq.ZMQSocketFactory.SocketBuilder;
import zmq.io.mechanism.Mechanisms;
import zmq.io.mechanism.curve.Curve;

public class CurveTest {

    private static Logger logger;

    @Rule(order=1)
    public TemporaryFolder testFolder = new TemporaryFolder();

    @Rule(order=2)
    public ZMQFactory tctxt = new ZMQFactory(testFolder, "secure");

    @BeforeClass
    static public void configure() throws IOException {
        Tools.configure();
        logger = LogManager.getLogger();
        LogUtils.setLevel(logger, Level.TRACE, "loghub.zmq");
    }

    Certificate getCertificate(byte[] publicKey) throws InvalidKeyException, InvalidKeySpecException {
        NaclPublicKeySpec keyspec = new NaclPublicKeySpec(publicKey);
        return new NaclCertificate(ZMQHelper.NACLKEYFACTORY.generatePublic(keyspec));
    }

    PrivateKeyEntry getCertificate(byte[] publicKeyBytes, byte[] privateKeyBytes) throws InvalidKeyException, InvalidKeySpecException {
        NaclPublicKeySpec publicKeySpec = new NaclPublicKeySpec(publicKeyBytes);
        NaclPrivateKeySpec privateKeySpec = new NaclPrivateKeySpec(privateKeyBytes);
        PrivateKey privateKey = ZMQHelper.NACLKEYFACTORY.generatePrivate(privateKeySpec);
        PublicKey publicKey = ZMQHelper.NACLKEYFACTORY.generatePublic(publicKeySpec);
        return new PrivateKeyEntry(privateKey, new Certificate[] {new NaclCertificate(publicKey)});
    }

    @Test(timeout=5000)
    public void testSecureConnectOneWay() throws InterruptedException, ExecutionException, ZMQCheckedException, InvalidKeyException, InvalidKeySpecException {
        String rendezvous = "tcp://localhost:" + Tools.tryGetPort();
        SocketBuilder serverBuilder = tctxt.getFactory().getBuilder(Method.BIND, SocketType.PULL, rendezvous)
                        .setHwm(100)
                        .setTimeout(1000)
                        .setSecurity(Mechanisms.CURVE)
                        .setCurveKeys(tctxt.getFactory().getKeyEntry())
                        .setCurveServer()
                        ;
        SocketBuilder clientBuilder = tctxt.getFactory().getBuilder(Method.CONNECT, SocketType.PUSH, rendezvous)
                        .setHwm(100)
                        .setTimeout(1000)
                        .setSecurity(Mechanisms.CURVE)
                        .setCurveKeys(tctxt.getFactory().getKeyEntry())
                        .setCurveClient(tctxt.getFactory().getKeyEntry().getCertificate())
                        ;
        try (Socket server = serverBuilder.build();
             Socket client = clientBuilder.build()) {
            AtomicBoolean run = new AtomicBoolean(true);
            Thread ts = ThreadBuilder.get().setTask(() -> {
                try (ZMonitor zmon = tctxt.getFactory().getZMonitor(server) ) {
                    zmon.add(ZMonitor.Event.ALL);
                    zmon.verbose(true);
                    zmon.start();
                    ZEvent event;
                    while ((event = zmon.nextEvent(1000)) != null && run.get()) {
                        System.out.println("server: " + event);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (zmq.ZError.IOException e) {
                    e.getCause().printStackTrace();
                }
                System.out.println("Done server");
            }).setDaemon(true).build(true);
            Thread tc = ThreadBuilder.get().setTask(() -> {
                try (ZMonitor zmon = tctxt.getFactory().getZMonitor(client)) {
                    zmon.add(ZMonitor.Event.ALL);
                    zmon.start();
                    ZEvent event;
                    while ((event = zmon.nextEvent(1000)) != null && run.get()) {
                        System.out.println("client: " + event);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (zmq.ZError.IOException e) {
                    e.getCause().printStackTrace();
                }
                System.out.println("Done client");
            }).setDaemon(true).build(true);
            Assert.assertEquals(ZMQ.Socket.Mechanism.CURVE,
                                server.getMechanism());
            Assert.assertEquals(ZMQ.Socket.Mechanism.CURVE,
                                client.getMechanism());
            try {
                client.send("Hello, World!");
                Assert.assertEquals("Hello, World!", server.recvStr());
            } finally {
                run.set(false);
                ts.join();
                tc.join();
            }
        }
    }

    @Test(timeout=5000)
    public void testSecureConnectOtherWay() throws InterruptedException, ExecutionException, ZMQCheckedException, InvalidKeyException, InvalidKeySpecException {
        Curve curve = new Curve();
        byte[][] serverKeys = curve.keypair();

        String rendezvous = "tcp://localhost:" + Tools.tryGetPort();
        SocketBuilder serverBuilder = tctxt.getFactory().getBuilder(Method.CONNECT, SocketType.PULL, rendezvous)
                        .setHwm(100)
                        .setTimeout(1000)
                        .setSecurity(Mechanisms.CURVE)
                        .setCurveKeys(getCertificate(serverKeys[0], serverKeys[1]))
                        .setCurveServer();
        ;
        SocketBuilder clientBuilder = tctxt.getFactory().getBuilder(Method.BIND, SocketType.PUSH, rendezvous)
                        .setHwm(100)
                        .setTimeout(1000)
                        .setSecurity(Mechanisms.CURVE)
                        .setCurveClient(getCertificate(serverKeys[0]))
                        ;
        try (Socket server = serverBuilder.build();
                        Socket client = clientBuilder.build()) {

            Assert.assertEquals(ZMQ.Socket.Mechanism.CURVE, server.getMechanism());
            Assert.assertEquals(ZMQ.Socket.Mechanism.CURVE, client.getMechanism());
            client.send("Hello, World!");
            Assert.assertEquals("Hello, World!", server.recvStr());
        } finally {
            tctxt.getFactory().close();
        }
    }

    @Test(timeout=5000)
    public void testFailedSecureConnect() throws InterruptedException, ExecutionException, ZMQCheckedException, InvalidKeyException, InvalidKeySpecException {
        Curve curve = new Curve();
        byte[][] serverKeys = curve.keypair();

        String rendezvous = "tcp://localhost:" + Tools.tryGetPort();
        SocketBuilder serverBuilder = tctxt.getFactory().getBuilder(Method.CONNECT, SocketType.PULL, rendezvous)
                        .setHwm(100)
                        .setTimeout(1000)
                        .setSecurity(Mechanisms.CURVE)
                        .setCurveKeys(getCertificate(serverKeys[0], serverKeys[1]))
                        .setCurveServer();
        ;
        SocketBuilder clientBuilder = tctxt.getFactory().getBuilder(Method.BIND, SocketType.PUSH, rendezvous)
                        .setHwm(100)
                        .setTimeout(1000)
                        .setSecurity(Mechanisms.CURVE)
                        .setCurveClient(getCertificate(serverKeys[1]))
                        ;
        try (Socket server = serverBuilder.build();
             Socket client = clientBuilder.build()) {

            Assert.assertEquals(ZMQ.Socket.Mechanism.CURVE, server.getMechanism());
            Assert.assertEquals(ZMQ.Socket.Mechanism.CURVE, client.getMechanism());
            client.send("Hello, World!");
            Assert.assertEquals(null, server.recvStr());
        } finally {
            tctxt.getFactory().close();
        }
    }

    @Test
    public void testEncoding() throws NoSuchAlgorithmException, InvalidKeyException, InvalidKeySpecException {
        KeyFactory kf = KeyFactory.getInstance(NaclProvider.NAME);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(kf.getAlgorithm());
        kpg.initialize(256);
        KeyPair kp = kpg.generateKeyPair();
        NaclCertificate certificate = new NaclCertificate(kp.getPublic());
        NaclPublicKeySpec pubkey = kf.getKeySpec(certificate.getPublicKey(), NaclPublicKeySpec.class);
    }

}
