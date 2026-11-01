/** Creates sockets that are exempt from the VPN before they connect. */
private class ProtectedSocketFactory(private val network: UpstreamNetwork) : SocketFactory() {

    override fun createSocket(): Socket = newProtectedSocket(null)

    override fun createSocket(host: String, port: Int): Socket =
        connected(InetSocketAddress(network.resolve(host).first(), port), null)

    override fun createSocket(
        host: String,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = connected(
        InetSocketAddress(network.resolve(host).first(), port),
        InetSocketAddress(localAddress, localPort)
    )

    override fun createSocket(host: InetAddress, port: Int): Socket =
        connected(InetSocketAddress(host, port), null)

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = connected(
        InetSocketAddress(address, port),
        InetSocketAddress(localAddress, localPort)
    )

    /** Binds to [local] first when one is given, then exempts the socket from the VPN. */
    private fun newProtectedSocket(local: InetSocketAddress?): Socket {
        val socket = Socket()
        try {
            if (local != null) socket.bind(local)
            if (!network.protect(socket)) {
                throw IOException("Could not exempt the socket from the VPN")
            }
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    private fun connected(remote: InetSocketAddress, local: InetSocketAddress?): Socket {
        val socket = newProtectedSocket(local)
        try {
            socket.connect(remote)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }
}
