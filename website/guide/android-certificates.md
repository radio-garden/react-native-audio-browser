# Android: Missing Intermediate Certificates

Some HTTPS servers are misconfigured to send only their **leaf** certificate and
omit the **intermediate** certificate(s) needed to chain up to a trusted root. A
correctly configured server sends the full chain; a misconfigured one leaves the
client to find the gap on its own.

- **iOS and web browsers** recover automatically: they follow the leaf's
  Authority Information Access (AIA) "CA Issuers" pointer to fetch the missing
  intermediate.
- **Android's default trust manager does not.** It validates only what the server
  sent, so playback of such a stream fails with:

  ```
  javax.net.ssl.SSLHandshakeException:
    java.security.cert.CertPathValidatorException:
      Trust anchor for certification path not found.
  ```

This affects media playback because the Android player transports audio over
`HttpURLConnection`, which uses the process-wide default `SSLSocketFactory`.

## Opt in

The library ships an **additive** AIA-chasing `SSLSocketFactory` but does **not**
install it for you — installing it sets process-global TLS state, which is a
decision for the host application to make, not a library. Enable it by setting it
as the default in your `Application.onCreate`, before any playback begins:

```kotlin
import android.app.Application
import android.util.Log
import com.audiobrowser.tls.AiaTls
import javax.net.ssl.HttpsURLConnection

class MainApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    try {
      HttpsURLConnection.setDefaultSSLSocketFactory(AiaTls.socketFactory())
    } catch (e: Exception) {
      // Never let TLS setup block startup; playback keeps the default behaviour.
      Log.w("MainApplication", "Failed to install AIA-chasing TLS", e)
    }
    // ... the rest of your setup
  }
}
```

That's all. The player's data source picks up the process default automatically.

## How it works

`AiaTls.socketFactory()` wraps the **platform default** trust manager. On the
happy path the default accepts the server-presented chain on the first try and
nothing extra happens. Only when the default *rejects* a chain does it:

1. read the leaf's AIA "CA Issuers" URL,
2. fetch the missing intermediate (cached for the process lifetime),
3. re-validate the completed chain **against the same system trust anchors**.

A fetched certificate is used only if it is genuinely the issuer of the preceding
one. Because the final decision is still the system trust manager's, this can
**only ever add a missing intermediate — it can never weaken trust**. Untrusted,
expired, or self-signed roots still fail exactly as before.

## Caveats

- **Scope.** `setDefaultSSLSocketFactory` is process-wide, so it affects every
  `HttpURLConnection`-based TLS connection in your app, not only playback. (It
  does not affect OkHttp or a `WebView`, which keep their own TLS stacks.) It is
  also last-write-wins: if other code sets its own default afterwards, it
  overrides this one.
- **First-connection latency on broken servers.** When a server omits its
  intermediate, the first connection performs one extra HTTP request for the
  intermediate during the TLS handshake. Well-configured servers pay no cost.
- **Not a cure-all.** It only rescues servers that omit an intermediate *and*
  publish an AIA "CA Issuers" pointer that resolves to a trusted root. Servers
  with no AIA extension, an unreachable intermediate, or a genuinely untrusted
  root still fail.

## Advanced

If you manage your own HTTP client (for example a custom OkHttp `DataSource`),
use `AiaTls.trustManager()` to obtain the wrapped `X509TrustManager` directly and
install it on that client instead of changing the process default.
