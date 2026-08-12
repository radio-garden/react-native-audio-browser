# Android: SSL "Trust anchor for certification path not found" (missing intermediate certificates)

**If Android playback of a radio stream (or any HTTPS audio source) fails with an
`SSLHandshakeException` / `CertPathValidatorException` / "Trust anchor for
certification path not found" — and the same stream plays fine on iOS and in a
desktop browser — you are in the right place.** This is almost always a server
that omits an intermediate certificate; the fix is to let the client chase the
missing certificate via the leaf's Authority Information Access (AIA) pointer, and
this guide shows the one-time Android setup that does it.

## The problem

Some HTTPS servers are misconfigured to send only their **leaf** certificate and
omit the **intermediate** certificate(s) needed to chain up to a trusted root. A
correctly configured server sends the full chain; a misconfigured one leaves the
client to find the gap on its own.

- **iOS and web browsers** recover automatically: they follow the leaf's
  Authority Information Access (AIA) "CA Issuers" pointer to fetch the missing
  intermediate. (This is why the stream works everywhere except Android.)
- **Android's default trust manager does not.** It validates only what the server
  sent, so playback of such a stream fails with:

  ```
  javax.net.ssl.SSLHandshakeException:
    java.security.cert.CertPathValidatorException:
      Trust anchor for certification path not found.
  ```

This affects media playback because the Android player transports audio over
`HttpURLConnection`, which uses the process-wide default `SSLSocketFactory`.

> **iOS needs no setup.** Apple's TLS stack chases AIA intermediates on its own,
> so there is nothing to configure on iOS — this guide is Android-only.

## Is this actually your problem?

Before changing process-global TLS state, confirm the failing stream really is a
missing-intermediate case and not some other TLS failure (an expired cert, an SNI
mismatch, or a genuinely untrusted root — none of which this fix addresses).

Run, against your failing stream's **host** — use just the hostname, not the full
stream URL. If your stream URL is `https://example.com:8000/live.mp3`, the host is
`example.com` and the port is `8000` (omit the path); plain `https://` URLs with no
port use `443`:

```sh
openssl s_client -connect example.com:443 \
  -servername example.com -showcerts </dev/null
```

In the output:

- A **complete** chain lists two or more certificates under
  `Certificate chain` (leaf `s:.../CN=host` then one or more issuers `i:...`) and
  ends with `Verify return code: 0 (ok)`.
- A **missing-intermediate** server typically shows **only one** certificate in
  the chain and a verify error such as
  `Verify return code: 21 (unable to verify the first certificate)` — yet the
  leaf's AIA block contains a `CA Issuers - URI:` pointer. That combination is the
  exact case this fix repairs.

Prefer a browser? Paste the host into the [SSL Labs server
test](https://www.ssllabs.com/ssltest/) — it flags **"Chain issues: Incomplete"**
for this exact problem — or use [whatsmychaincert.com](https://whatsmychaincert.com/).

> **Want to see a broken chain first?** `incomplete-chain.badssl.com` is a public
> test host that deliberately omits its intermediate — the canonical example of
> this misconfiguration. Run the command above against
> `incomplete-chain.badssl.com:443` to see exactly what the "one certificate +
> verify error + AIA pointer present" signature looks like before you go hunting in
> your own stream.

If the chain is complete, or there is no `CA Issuers` AIA pointer, this fix will
**not** help — see [Caveats](#caveats).

## Opt in

The library ships an **additive** AIA-chasing `SSLSocketFactory` but does **not**
install it for you — installing it sets process-global TLS state, which is a
decision for the host application to make, not a library. Enable it by setting it
as the default in your **existing** `MainApplication`'s `onCreate`, before any
playback begins. `MainApplication.onCreate` runs before React Native loads and
executes your JS bundle, so it is always early enough — no playback can have
started yet.

> Add the highlighted line to the `MainApplication` you already have (it lives at
> `android/app/src/main/java/.../MainApplication.kt` or `.java`). **Don't create a
> new file or a second `MainApplication` class** — just drop the call into the
> `onCreate` you already have, after `super.onCreate()`.

::: code-group

```kotlin [MainApplication.kt]
import com.audiobrowser.tls.AiaTls
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import android.util.Log

class MainApplication : Application() {
  override fun onCreate() {
    super.onCreate()

    try {
      val aiaFactory = AiaTls.socketFactory()                   // [!code ++]
      HttpsURLConnection.setDefaultSSLSocketFactory(aiaFactory) // [!code ++]
      installedSslFactory = aiaFactory                          // [!code ++]
    } catch (e: Exception) {
      // Never let TLS setup block startup; playback keeps default behaviour.
      Log.w("MainApplication", "Failed to install AIA-chasing TLS", e)
    }

    // ... the rest of your existing setup
  }

  companion object {
    /** Kept so you can confirm the install — see "Verify it worked". */
    var installedSslFactory: SSLSocketFactory? = null
  }
}
```

```java [MainApplication.java]
import com.audiobrowser.tls.AiaTls;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import android.util.Log;

public class MainApplication extends Application {
  /** Kept so you can confirm the install — see "Verify it worked". */
  public static SSLSocketFactory installedSslFactory = null;

  @Override
  public void onCreate() {
    super.onCreate();

    try {
      SSLSocketFactory aiaFactory = AiaTls.socketFactory();        // [!code ++]
      HttpsURLConnection.setDefaultSSLSocketFactory(aiaFactory);   // [!code ++]
      installedSslFactory = aiaFactory;                            // [!code ++]
    } catch (Exception e) {
      // Never let TLS setup block startup; playback keeps default behaviour.
      Log.w("MainApplication", "Failed to install AIA-chasing TLS", e);
    }

    // ... the rest of your existing setup
  }
}
```

:::

That's all. The player's data source picks up the process default automatically.
`AiaTls` ships with the `react-native-audio-browser` Android module you already
installed — the `com.audiobrowser.tls` import resolves with no extra Gradle
dependency.

> **Rebuild after adding this.** `AiaTls` is native Kotlin, so a JS-only reload
> won't pick up the new call — do a full native rebuild:
>
> ```sh
> npx react-native run-android
> # if the build seems stale, clear Gradle first:
> cd android && ./gradlew clean && cd .. && npx react-native run-android
> ```
>
> No manifest or permission change is needed — RN apps already hold the `INTERNET`
> permission the AIA fetch uses, and the cleartext fetch is handled for you (see
> [The cleartext fetch is handled for you](#the-cleartext-fetch-is-handled-for-you)).

## The cleartext fetch is handled for you

The completed-chain check happens over HTTPS — only the side fetch of the _missing
intermediate_ uses the leaf's `CA Issuers` URL, which CAs publish over plain
`http://` (mandated by the CA/Browser Forum Baseline Requirements, to avoid a
chicken-and-egg TLS dependency when fetching the very certificate needed to
complete a TLS chain).

That's a problem on most modern apps: targeting Android 9+ (API 28) **blocks
cleartext HTTP by default**, which would silently break the fetch on release
builds. So the library doesn't use Android's HTTP stack for it — the `http`
intermediate fetch goes over a **raw socket**, which isn't subject to your app's
`usesCleartextTraffic` / network-security-config policy. **You don't need to enable
cleartext or add a network security config.**

This stays safe: the fetched certificate is cryptographically verified to be the
genuine issuer and re-validated against the system trust anchors before use, so a
tampered response over plain HTTP can only fail the chain, never weaken it.

## Verify it worked

A silent success is hard to tell apart from "my problem was something else," so
confirm it explicitly:

1. **Confirm the factory is installed.** The `catch` above only logs on _failure_,
   so **no warning at startup means it installed without throwing** — enough for a
   simple app. But it is _not_ enough if your app uses other networking libraries:
   `setDefaultSSLSocketFactory` is last-write-wins (see [Caveats](#caveats)), so
   another library setting its own default afterward silently deactivates the fix
   with no warning. The comparison below is the **only reliable check** in that
   case. `AiaTls.socketFactory()` returns a stock TLS socket factory configured
   with the AIA-chasing trust manager, so its _class name_ looks identical to the
   platform default — don't try to recognise it by class. Instead compare the
   process default against the reference the opt-in snippet stored in
   `installedSslFactory`. Put this **after** the install `try`/`catch` (so it
   reflects the real process default, not the value you just set), anywhere that
   runs after startup:

   ::: code-group

   ```kotlin [Kotlin]
   // place OUTSIDE the install try/catch — this tests the live process default
   val active = HttpsURLConnection.getDefaultSSLSocketFactory() ===
     MainApplication.installedSslFactory
   Log.i("MainApplication", "AIA TLS active: $active")
   ```

   ```java [Java]
   // place OUTSIDE the install try/catch — this tests the live process default
   boolean active = HttpsURLConnection.getDefaultSSLSocketFactory()
     == MainApplication.installedSslFactory;
   Log.i("MainApplication", "AIA TLS active: " + active);
   ```

   :::

2. **Confirm playback recovers.** Play the exact stream that previously failed.
   It should now start, and the
   `SSLHandshakeException: ... Trust anchor for certification path not found`
   should no longer appear in `adb logcat`. No real broken stream on hand? Point
   the player at a known-broken-chain source such as
   `https://incomplete-chain.badssl.com/` to prove the wiring end-to-end before you
   rely on it. Keep one of these around as a regression check for future upgrades.

### Still failing after install?

Work through these in order:

1. **Is it even a missing-intermediate case?** Re-run the
   [diagnosis](#is-this-actually-your-problem). If the chain is complete, there's
   no `CA Issuers` AIA pointer, or the root is genuinely untrusted/expired, this
   fix can't repair it — that's a different problem.
2. **Did the AIA fetch fail?** When the chaser tries to fetch the intermediate and
   can't, the library logs a warning:

   ```
   AIA CA-issuer fetch failed: http://…/issuer.crt
   ```

   Filter `adb logcat` for `AIA CA-issuer fetch failed`. If it appears, the fetch
   was attempted but couldn't be reached — see the next point. (You do _not_ need
   to enable cleartext; the library's fetch already bypasses that policy.)

   ::: warning The logger starts with the playback service
   The library plants its logging trees when its service first starts, so a fetch
   that fails **before** any playback has begun logs nothing — even though the
   socket factory is process-wide and already in use. If you see no line at all,
   start playback once and retry, or plant your own tree at startup.
   :::

3. **Reachable from your environment?** The fetch is a plain outbound request to
   the `CA Issuers - URI` during the handshake, so a corporate proxy, VPN,
   firewall, or a locked-down emulator network can block it. Confirm the URL
   resolves by fetching it yourself — `curl -I "http://…/issuer.crt"` — from the
   same network the device is on.
4. **Custom Network Security Config?** If your app ships a
   `res/xml/network_security_config.xml` with custom trust anchors or pinning,
   that's an unrelated cause of TLS failures — this fix won't override it.

## How it works

`AiaTls.socketFactory()` wraps the **platform default** trust manager. On the
happy path the default accepts the server-presented chain on the first try and
nothing extra happens. Only when the default _rejects_ a chain does it:

1. read the leaf's AIA "CA Issuers" URL,
2. fetch the missing intermediate (over a raw socket for `http` URLs, so it works
   regardless of your cleartext policy; cached for the process lifetime),
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
  intermediate during the TLS handshake. Well-configured servers pay no cost. The
  fetched intermediate is cached for the **process lifetime only**, so this
  one-time cost recurs once per cold start for each broken server.
- **Not a cure-all.** It only rescues servers that omit an intermediate _and_
  publish an AIA "CA Issuers" pointer that resolves to a trusted root. Servers
  with no AIA extension, an unreachable intermediate, or a genuinely untrusted
  root still fail.
- **The AIA fetch needs outbound network reachability.** Completing the chain
  performs a plain HTTP request to the `CA Issuers` host during the handshake; a
  proxy, VPN, or firewall that blocks it makes the fix look broken even on a
  genuinely missing-intermediate server. See
  [Still failing after install?](#still-failing-after-install) to diagnose.
- **Unrelated cause: custom Network Security Config.** A
  `res/xml/network_security_config.xml` with custom trust anchors or certificate
  pinning is a _different_ source of TLS failures that this fix does not address.

## What you are opting into

Trust is never weakened. The chain is only ever _extended_, and the completed
chain is re-validated by the platform's own trust manager against the same
system anchors, so anything Android would have rejected is still rejected.

What you are accepting is an outbound request driven by a certificate that just
failed validation. Completing a chain means fetching the missing intermediate
from a URL written into that certificate, so a rejected handshake can cause a
cleartext request to a **host, port and path of the server's choosing** —
including a loopback or LAN address — and that request is deliberately not
subject to your app's `NetworkSecurityPolicy` (see
[The cleartext fetch is handled for you](#the-cleartext-fetch-is-handled-for-you)).

This is inherent to AIA chasing: browsers and Apple's Secure Transport do the
same thing, which is why the servers in question work everywhere except Android.
It is bounded rather than absent:

- the fetch happens **only** when the chain does not already reach one of your
  trust anchors, so the common non-path failures — an expired leaf, a hostname
  mismatch — do not trigger one. (The test is on the issuer name at the top of
  the chain, so a chain topping at a root your device does _not_ carry, such as
  a server still serving the retired DST Root CA X3 cross-sign, is still chased
  even if its real problem is expiry.)
- the URL is rejected unless it is free of control characters, so it cannot
  inject request lines of its own;
- at most 3 distinct `CA Issuers` URLs per certificate are tried, a response is
  capped at 1 MiB, and at most 5 redirects are followed per fetch;
- the whole chase runs under a single 20-second wall-clock budget — every
  connect, TLS handshake, read and redirect hop is clipped to the time
  remaining, and nothing new starts once it has expired. So the worst case a
  hostile certificate can inflict on one failed handshake is roughly those
  20 seconds, plus at most one DNS lookup that was already in flight when the
  budget expired (Java offers no timeout for name resolution, so a lookup can
  overrun the budget by its own duration — but a new one is never started past
  it);
- the cache holds at most 32 entries, and only successful fetches;
- responses are never trusted on their say-so: a fetched certificate is used
  only if it is the genuine issuer of the certificate below it, and the
  completed chain still has to validate.

If your threat model does not allow a failed handshake to trigger an outbound
connection at all, don't install this — or install it on a single client
(see [Advanced: custom OkHttp client](#advanced-custom-okhttp-client)) rather
than as the process default.

## Advanced: custom OkHttp client

If you manage your own HTTP client instead of changing the process default, use
`AiaTls.trustManager()` to obtain the wrapped `X509TrustManager` and build a
matching `SSLSocketFactory` from it. OkHttp's `sslSocketFactory(...)` needs
**both** the factory and the trust manager:

::: code-group

```kotlin [Kotlin]
import com.audiobrowser.tls.AiaTls
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient

val trustManager = AiaTls.trustManager()
val sslContext = SSLContext.getInstance("TLS").apply {
  init(null, arrayOf(trustManager), null)
}

val client = OkHttpClient.Builder()
  .sslSocketFactory(sslContext.socketFactory, trustManager)
  .build()
```

```java [Java]
import com.audiobrowser.tls.AiaTls;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;

X509TrustManager trustManager = AiaTls.trustManager();
SSLContext sslContext = SSLContext.getInstance("TLS");
sslContext.init(null, new X509TrustManager[] { trustManager }, null);

OkHttpClient client = new OkHttpClient.Builder()
  .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
  .build();
```

:::

This confines AIA chasing to that one client and leaves the process default
untouched.

> **Lifecycle.** Each call to `AiaTls.socketFactory()` / `AiaTls.trustManager()`
> builds a fresh component with its **own** fetched-intermediate cache, so build
> once and reuse it (don't construct one per request). The components are
> thread-safe — the fetch cache is a synchronized, bounded LRU — so a single
> instance is safe to install as the process default or share across a client.
