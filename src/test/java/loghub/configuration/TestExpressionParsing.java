package loghub.configuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import javax.management.remote.JMXPrincipal;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import loghub.Event;
import loghub.Expression;
import loghub.Expression.ExpressionException;
import loghub.IpConnectionContext;
import loghub.LogUtils;
import loghub.ProcessorException;
import loghub.RouteLexer;
import loghub.RouteParser;
import loghub.RouteParser.ExpressionContext;
import loghub.Tools;
import loghub.VarFormatter;
import loghub.configuration.ConfigListener.ObjectWrapped;
import loghub.receivers.Http;

public class TestExpressionParsing {


    private static Logger logger ;

    @BeforeClass
    static public void configure() throws IOException {
        Tools.configure();
        logger = LogManager.getLogger();
        LogUtils.setLevel(logger, Level.TRACE, "loghub.configuration", "loghub.Expression", "loghub.VarFormatter");
    }

    private String parseExpression(String exp) {
        CharStream cs = CharStreams.fromString(exp);
        RouteLexer lexer = new RouteLexer(cs);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RouteParser parser = new RouteParser(tokens);
        parser.removeErrorListeners();
        ConfigErrorListener errListener = new ConfigErrorListener();
        parser.addErrorListener(errListener);

        ExpressionContext tree = parser.expression();
        ConfigListener conf = new ConfigListener();
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(conf, tree);
        ObjectWrapped parsed = (ObjectWrapped) conf.stack.pop();
        Assert.assertTrue(conf.stack.isEmpty());
        return (String) parsed.wrapped;
    }

    private Object evalExpression(String exp, Event ev, Map<String, VarFormatter> formats) throws ExpressionException, ProcessorException {
        Expression expression = new Expression(parseExpression(exp), new Properties(Collections.emptyMap()).groovyClassLoader, formats);
        return expression.eval(ev);
    }

    private Object evalExpression(String exp, Event ev) throws ExpressionException, ProcessorException {
        return evalExpression(exp, ev, Collections.emptyMap());
    }

    private Object evalExpression(String exp) throws ExpressionException, ProcessorException {
        return evalExpression(exp, Tools.getEvent());
    }

    @Test
    public void testSimple() throws ExpressionException, ProcessorException {
        Assert.assertEquals("3", evalExpression("1 + 2").toString());
    }

    @Test
    public void testOr() throws ExpressionException, ProcessorException {
        Assert.assertEquals("3", evalExpression("1 .| 2").toString());
    }

    @Test
    public void testUnary() throws ExpressionException, ProcessorException {
        Assert.assertEquals("-2", evalExpression("~1").toString());
        Assert.assertEquals("false", evalExpression("!1").toString());
    }

    @Test
    public void testSubExpression() throws ExpressionException, ProcessorException {
        Assert.assertEquals("12", evalExpression("(1 + 2) * 4").toString());
    }

    @Test
    public void testNew() throws ExpressionException, ProcessorException {
        Date newdate = (Date)evalExpression("new java.util.Date(1+2)");
        Assert.assertEquals(new Date(3), newdate);
    }

    @Test
    public void testFormatterSimple() throws ExpressionException, ProcessorException {
        String format = "${#1%02d}";
        String formatHash = Integer.toHexString(format.hashCode());
        Event ev =  Tools.getEvent();
        ev.put("a", 1);
        Assert.assertEquals("01", evalExpression("\"" + format + "\"([a])", ev, Collections.singletonMap("h_" + formatHash, new VarFormatter(format))).toString());
    }

    @Test(expected=ProcessorException.class)
    public void testFormatterFailed() throws ExpressionException, ProcessorException {
        String format = "${#2%02d}";
        String formatHash = Integer.toHexString(format.hashCode());
        Event ev =  Tools.getEvent();
        ev.put("a", 1);
        try {
            evalExpression("\"" + format + "\"([a])", ev, Collections.singletonMap("h_" + formatHash, new VarFormatter(format)));
        } catch (ProcessorException e) {
            Assert.assertTrue(e.getMessage().contains("index out of range"));
            throw e;
        }
    }

    @Test
    public void testFormatterTimestamp() throws ExpressionException, ProcessorException {
        String format = "${#1%t<Europe/Paris>H}";
        String formatHash = Integer.toHexString(format.hashCode());
        Event ev =  Tools.getEvent();
        ev.setTimestamp(new Date(0));
        Assert.assertEquals("01", evalExpression("\"" + format + "\"([@timestamp])", ev, Collections.singletonMap("h_" + formatHash, new VarFormatter(format))).toString());
    }

    @Test
    public void testFormatterContextPrincipal() throws ExpressionException, ProcessorException {
        String format = "${#1%s}-${#2%tY}.${#2%tm}.${#2%td}";
        String formatHash = Integer.toHexString(format.hashCode());
        Event ev =  Tools.getEvent();
        ev.setTimestamp(new Date(0));
        Principal p = new JMXPrincipal("user");
        ev.getConnectionContext().setPrincipal(p);
        Assert.assertEquals("user-1970.01.01", evalExpression("\"" + format + "\" ([@context principal name], [@timestamp])", ev, Collections.singletonMap("h_" + formatHash, new VarFormatter(format))).toString());
    }

    @Test
    public void testEventPath() throws ExpressionException, ProcessorException {
        Event ev =  Tools.getEvent();
        ev.put("a", Collections.singletonMap("b", "c"));
        Assert.assertEquals("c", evalExpression("[a b]", ev));
    }

    @Test
    public void testTimestamp() throws ExpressionException, ProcessorException {
        Event ev =  Tools.getEvent();
        ev.setTimestamp(new Date(0));
        Date ts = (Date) evalExpression("[ @timestamp ]",ev);
        Assert.assertEquals(0L, ts.getTime());
    }

    @Test
    public void testContext() throws ExpressionException, ProcessorException {
        String format = "user";
        String formatHash = Integer.toHexString(format.hashCode());

        Event ev =  Event.emptyEvent(new IpConnectionContext(new InetSocketAddress("127.0.0.1", 35710), new InetSocketAddress("localhost", 80), null));
        Principal p = new JMXPrincipal("user");
        ev.getConnectionContext().setPrincipal(p);
        Object value = evalExpression("[ @context principal name ] == \"user\"", ev, Collections.singletonMap("h_" + formatHash, new VarFormatter(format)));
        Assert.assertEquals(true, value);
        InetSocketAddress localAddr = (InetSocketAddress) evalExpression("[ @context localAddress]", ev, Collections.singletonMap("h_" + formatHash, new VarFormatter(format)));
        Assert.assertEquals(35710, localAddr.getPort());
    }

}
