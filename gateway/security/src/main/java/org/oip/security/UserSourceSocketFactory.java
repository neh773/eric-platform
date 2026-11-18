package org.oip.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import org.oip.config.OutboundTls;

/**
 * The TLS socket factory a directory user source (SEC-007) uses for {@code ldaps://} and StartTLS: the gateway's
 * outbound trust (SEC-022), read when each connection opens, so a certificate trusted later applies to the next
 * login. JNDI loads it by class name and calls the static {@link #getDefault()}, which is why it is Java: a Kotlin
 * class cannot declare a static method that hides the one it inherits.
 *
 * Doc source: FEATURES SEC-007.
 */
public final class UserSourceSocketFactory extends SSLSocketFactory {
    /** The client name the outbound trust store shows beside a certificate it refused. */
    public static final String CLIENT = "user-source";

    private static final UserSourceSocketFactory SHARED = new UserSourceSocketFactory();

    private UserSourceSocketFactory() {}

    /** Called by JNDI through reflection. */
    public static SocketFactory getDefault() {
        return SHARED;
    }

    public static UserSourceSocketFactory shared() {
        return SHARED;
    }

    private static SSLSocketFactory delegate() {
        return OutboundTls.INSTANCE.context(CLIENT).getSocketFactory();
    }

    @Override public String[] getDefaultCipherSuites() { return delegate().getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate().getSupportedCipherSuites(); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return delegate().createSocket(s, host, port, autoClose); }
    @Override public Socket createSocket() throws IOException { return delegate().createSocket(); }
    @Override public Socket createSocket(String host, int port) throws IOException { return delegate().createSocket(host, port); }
    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException { return delegate().createSocket(host, port, localHost, localPort); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return delegate().createSocket(host, port); }
    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException { return delegate().createSocket(address, port, localAddress, localPort); }
}
