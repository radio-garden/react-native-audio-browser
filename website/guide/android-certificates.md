---
description: 'Why Android playback fails with SSLHandshakeException / "Trust anchor for certification path not found" on HTTPS streams whose server omits its intermediate certificate, and what the AIA toolkit does and does not reach.'
---

# Android: missing intermediate certificates

Android playback of an HTTPS stream can fail with `SSLHandshakeException`,
`CertPathValidatorException` or "Trust anchor for certification path not found"
while the same stream plays on iOS and in a desktop browser. The cause is almost
always a server that omits an intermediate certificate.

The library ships `AiaTls`, a toolkit that fetches the missing certificate from
the URL in the leaf's Authority Information Access (AIA) extension. It does not
reach playback yet.

The player sends media over its own OkHttp client, which the app cannot
configure, so installing `AiaTls` does not fix a failing stream.

The reliable fix is for the server to send its full chain. The client-side
alternative is a Network Security Config that trusts the missing intermediate (see
[Fix playback from the app](#fix-playback-from-the-app)). This guide shows how
to confirm the diagnosis, and where `AiaTls` does apply.

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

The player sends media over its own OkHttp client, which validates with the
platform default trust manager. OkHttp does not read the process-wide default
`SSLSocketFactory`, and the library offers no way to give that client another
one.

Nothing the app sets as a socket factory reaches playback. Only a Network
Security Config does, because the platform trust manager reads it.

> **iOS needs no setup.** Apple's TLS stack chases AIA intermediates on its own,
> so there is nothing to configure on iOS — this guide is Android-only.

## Is this actually your problem?

Before you report the stream to its operator or change TLS state, confirm the
failing stream really is a missing-intermediate case. Other TLS failures look
alike: an expired cert, an SNI mismatch, or a genuinely untrusted root.
`AiaTls` addresses none of them.

Run, against your failing stream's **host** — use just the hostname, not the full
stream URL. If your stream URL is `https://example.com:8000/live.mp3`, the host is
`example.com` and the port is `8000` (omit the path); plain `https://` URLs with no
port use `443`:

```sh
openssl s_client -connect example.com:443 \
  -servername example.com -showcerts </dev/null
```

This command does not decode the leaf's extensions. To see its AIA pointer,
decode the leaf:

```sh
openssl s_client -connect example.com:443 \
  -servername example.com </dev/null 2>/dev/null |
  openssl x509 -noout -ext authorityInfoAccess
```

In the output:

- A **complete** chain lists two or more certificates under
  `Certificate chain` (leaf `s:.../CN=host` then one or more issuers `i:...`) and
  ends with `Verify return code: 0 (ok)`.
- A **missing-intermediate** server typically shows **only one** certificate in
  the chain and a verify error such as
  `Verify return code: 21 (unable to verify the first certificate)`.
- A `CA Issuers - URI:` line in the second command's output is the pointer
  `AiaTls` follows. One certificate, a verify error and this pointer together
  are a missing intermediate. For playback, the server operator has to send
  the full chain, or the app trusts the intermediate (see
  [Fix playback from the app](#fix-playback-from-the-app)).

  `AiaTls` can complete it only for connections your app makes itself:
  `HttpURLConnection` through the process default, or an OkHttp client you
  build (see [Advanced](#advanced-custom-okhttp-client)).

Prefer a browser? Paste the host into the [SSL Labs server
test](https://www.ssllabs.com/ssltest/) — it flags **"Chain issues: Incomplete"**
for this exact problem — or use [whatsmychaincert.com](https://whatsmychaincert.com/).

> **Want to see a broken chain first?** `incomplete-chain.badssl.com` is a public
> test host that omits its intermediate on purpose. Run both commands above
> against `incomplete-chain.badssl.com:443` to see the signature: one
> certificate, a verify error and a `CA Issuers` pointer.

If the chain is complete, or there is no `CA Issuers` AIA pointer, `AiaTls` will
**not** help — see [Caveats](#caveats).

## Fix playback from the app

The player's OkHttp client validates with the platform default trust manager.
On Android 7 (API 24) and later, that trust manager reads the app's Network
Security Config. So a `<domain-config>` for the stream's host can trust the
missing intermediate, with no change to the library.

1. Download the intermediate from the leaf's `CA Issuers - URI` and save it as
   `res/raw/stream_intermediate.crt` (PEM or DER).
2. Add `res/xml/network_security_config.xml`:

   ```xml
   <network-security-config>
     <domain-config>
       <domain includeSubdomains="false">example.com</domain>
       <trust-anchors>
         <certificates src="@raw/stream_intermediate" />
         <certificates src="system" />
       </trust-anchors>
     </domain-config>
   </network-security-config>
   ```

3. Point the `<application>` element of `AndroidManifest.xml` at it with
   `android:networkSecurityConfig="@xml/network_security_config"`.

The cost is upkeep. The app now bundles that certificate. When the CA rotates
its intermediate or the server changes CA, playback fails again until you ship
an update. Fixing the server's chain has no such cost.

## Opt in

The library ships an **additive** AIA-chasing `SSLSocketFactory` but does **not**
install it for you. Installing it changes process-global TLS state, so that is
the app's decision, not the library's.

::: warning This does not fix playback
The player sends media over the library's own OkHttp client, which ignores
`HttpsURLConnection`'s default. The install below covers your app's own
`HttpURLConnection` traffic only.

For a stream that fails in the player, fix the server's chain, or trust the intermediate in a Network Security Config (see
[Fix playback from the app](#fix-playback-from-the-app)).
:::

To enable it, set it as the default in the `onCreate` of your **existing**
`MainApplication`. `MainApplication.onCreate` runs before React Native loads your
JS bundle, so no connection can have started yet.

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
      // Never let TLS setup block startup; connections keep default behaviour.
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
      // Never let TLS setup block startup; connections keep default behaviour.
      Log.w("MainApplication", "Failed to install AIA-chasing TLS", e);
    }

    // ... the rest of your existing setup
  }
}
```

:::

This reaches every `HttpURLConnection` in the process. It does not reach the
player, which uses OkHttp (see the warning above).
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

The stream itself stays on HTTPS. Only the fetch of the _missing intermediate_
uses the leaf's `CA Issuers` URL, and CAs publish that URL over plain `http://`.
The CA/Browser Forum Baseline Requirements mandate this: a fetch over TLS would
need the very certificate that is missing.

Apps that target Android 9 (API 28) or later **block cleartext HTTP by default**,
which would break the fetch in release builds. So the library fetches `http`
intermediates over a **raw socket**, which your app's `usesCleartextTraffic` and
network security config do not govern.

**You don't need to enable cleartext or add a network security config.**

A fetched certificate joins the chain when its subject matches
the issuer name of the certificate below it. The platform trust manager then
validates the completed chain, signatures included, against the system trust
anchors. So a forged or wrong certificate can only fail the handshake, never
weaken trust.

## Verify it worked

A silent success is hard to tell apart from "my problem was something else," so
confirm it explicitly:

1. **Confirm the factory is installed.** The `catch` above logs only on
   _failure_, so **no warning at startup means the install did not throw**. In a
   simple app, that is enough.

   `setDefaultSSLSocketFactory` is last-write-wins (see [Caveats](#caveats)). If
   another networking library sets its own default later, the fix stops working
   with no warning. To catch that, compare the process default with the
   `installedSslFactory` reference that the opt-in snippet stored.

   Don't compare class names. `AiaTls.socketFactory()` returns a stock TLS socket
   factory with the AIA-chasing trust manager, so its class looks the same as the
   platform default.

   Run the comparison after your other networking libraries have initialised,
   for example on first playback or in a debug screen. In `onCreate`, next to the
   install, it is always true and proves nothing.

   ::: code-group

   ```kotlin [Kotlin]
   // Run after other networking libraries have initialised.
   val active = HttpsURLConnection.getDefaultSSLSocketFactory() ===
     MainApplication.installedSslFactory
   Log.i("MainApplication", "AIA TLS active: $active")
   ```

   ```java [Java]
   // Run after other networking libraries have initialised.
   boolean active = HttpsURLConnection.getDefaultSSLSocketFactory()
     == MainApplication.installedSslFactory;
   Log.i("MainApplication", "AIA TLS active: " + active);
   ```

   :::

2. **Confirm an `HttpURLConnection` request recovers.** Open
   `https://incomplete-chain.badssl.com/` with `HttpsURLConnection` from your
   app. Without the install it fails with
   `SSLHandshakeException: ... Trust anchor for certification path not found`;
   with it, it succeeds. A stream that fails in the player still fails, because
   the player does not use the process default.

### Still failing after install?

Work through these in order:

1. **Is the failure in playback?** The player does not use the process default,
   so the install cannot repair it. Fix the server's chain, or see
   [Fix playback from the app](#fix-playback-from-the-app).
2. **Is it even a missing-intermediate case?** Re-run the
   [diagnosis](#is-this-actually-your-problem). If the chain is complete, there's
   no `CA Issuers` AIA pointer, or the root is genuinely untrusted/expired, this
   fix can't repair it — that's a different problem.
3. **Did the AIA fetch fail?** When the chaser tries to fetch the intermediate and
   can't, the library logs a warning:

   ```
   AIA CA-issuer fetch failed: http://…/issuer.crt
   ```

   Filter `adb logcat` for `AIA CA-issuer fetch failed`. If it appears, the fetch
   was attempted but couldn't be reached — see the next point. (You do _not_ need
   to enable cleartext; the library's fetch already bypasses that policy.)

   ::: warning The logger starts with the playback service
   The library plants its logging trees when its service first starts. A fetch
   that fails **before** any playback has begun logs nothing, even though the
   socket factory is process-wide and already in use.

   If you see no line at all, start playback once and retry, or plant your own
   tree at startup.
   :::

4. **Reachable from your environment?** The fetch is a plain outbound request to
   the `CA Issuers - URI` during the handshake, so a corporate proxy, VPN,
   firewall, or a locked-down emulator network can block it. Confirm the URL
   resolves by fetching it yourself — `curl -I "http://…/issuer.crt"` — from the
   same network the device is on.
5. **Custom Network Security Config?** If your app ships a
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

A fetched certificate joins the chain when its subject matches the issuer name
of the certificate before it. The system trust manager makes the final decision
on the completed chain, signatures included. So this can **only ever add a
missing intermediate — it can never weaken trust**.

Untrusted, expired, or self-signed roots still fail exactly as before.

## Caveats

- **Scope.** `setDefaultSSLSocketFactory` is process-wide, so it affects every
  `HttpURLConnection`-based TLS connection in your app. It does not affect OkHttp
  or a `WebView`, which keep their own TLS stacks. That includes the player,
  whose media requests go over OkHttp.
- **Last write wins.** If other code sets its own default afterwards, it
  overrides this one.
- **First-connection latency on broken servers.** When a server omits its
  intermediate, the first connection makes one extra HTTP request during the TLS
  handshake. Well-configured servers pay nothing. The fetched intermediate is
  cached for the **process lifetime only**, so the cost recurs once per cold
  start for each broken server.
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
failed validation. The fetch URL comes from that certificate. So a rejected
handshake can cause a cleartext request to a **host, port and path of the
server's choosing**, including a loopback or LAN address.

That request is not subject to your app's `NetworkSecurityPolicy` (see
[The cleartext fetch is handled for you](#the-cleartext-fetch-is-handled-for-you)).

This is inherent to AIA chasing: browsers and Apple's Secure Transport do the
same thing, which is why the servers in question work everywhere except Android.
It is bounded rather than absent:

- the fetch happens **only** when the chain does not already reach one of your
  trust anchors. Common non-path failures, such as an expired leaf or a hostname
  mismatch, do not trigger one.
- that test is on the issuer name at the top of the chain. A chain that tops out
  at a root your device does _not_ carry is still chased, even if its real
  problem is expiry. An example is a server that serves the retired DST Root CA
  X3 cross-sign.
- the URL is rejected unless it is free of control characters, so it cannot
  inject request lines of its own;
- at most 3 distinct `CA Issuers` URLs per certificate are tried, a response is
  capped at 1 MiB, and at most 5 redirects are followed per fetch;
- the whole chase runs under one 20-second wall-clock budget. Every connect,
  TLS handshake, read and redirect hop is clipped to the time remaining, and
  nothing new starts once the budget has expired. So a hostile certificate can
  hold one failed handshake for about 20 seconds. One DNS lookup already in
  flight when the budget expires can add its own duration, because Java has no
  timeout for name resolution. No new lookup starts past the budget;
- the cache holds at most 32 entries, and only successful fetches;
- responses are never trusted on their say-so. A fetched certificate joins the
  chain only if its subject matches the issuer name being chased, and the
  completed chain still has to validate, signatures included.

If your threat model does not allow a failed handshake to trigger an outbound
connection at all, don't install this. Or install it on a single client of
your own (see [Advanced: custom OkHttp client](#advanced-custom-okhttp-client))
rather than as the process default.

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
untouched. The library has no option for handing such a client to its player,
so playback stays on the platform trust manager.

> **Lifecycle.** Each call to `AiaTls.socketFactory()` or `AiaTls.trustManager()`
> builds a new component with its **own** fetched-intermediate cache. Build one
> and reuse it; don't build one per request.
>
> The components are thread-safe: the fetch cache is a synchronized, bounded
> LRU. One instance is safe to install as the process default or to share
> across a client.
