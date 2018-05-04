package loghub.ssl;

import javax.net.ssl.SSLEngine;

public enum ClientAuthentication {
    REQUIRED {
        @Override
        public void configureEngine(SSLEngine engine) {
            engine.setNeedClientAuth(true);
        }
    },
    WANTED {
        @Override
        public void configureEngine(SSLEngine engine) {
            engine.setWantClientAuth(true);
        }
    },
    NOTNEEDED {
        @Override
        public void configureEngine(SSLEngine engine) {
            engine.setNeedClientAuth(false);
            engine.setWantClientAuth(false);
        }
    };
    public abstract void configureEngine(SSLEngine engine);
}
