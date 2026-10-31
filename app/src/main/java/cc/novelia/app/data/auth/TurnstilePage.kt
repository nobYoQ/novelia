package cc.novelia.app.data.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class VerificationRoute { APP, CLOUDFLARE, BLOCK }

/** Load the real auth page, including its current widget configuration, through the app transport. */
internal fun verificationPageUrl(target: AuthTarget, resetting: Boolean, dark: Boolean): String =
    Session.AUTH_URL.toHttpUrl().newBuilder()
        .addPathSegment(if(resetting) "reset-password" else "register")
        .addQueryParameter("app", target.appId)
        .addQueryParameter("theme", if(dark) "dark" else "light")
        .build().toString()

/** Only Turnstile can use WebView's network. All business APIs and other destinations are denied. */
internal fun verificationRoute(url: String, method: String, mainFrame: Boolean, pageUrl: String): VerificationRoute {
    val parsed = url.toHttpUrlOrNull() ?: return VerificationRoute.BLOCK
    if(!parsed.isHttps || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty())
        return VerificationRoute.BLOCK
    if(parsed.host == "challenges.cloudflare.com" && !mainFrame) return VerificationRoute.CLOUDFLARE
    if(parsed.host != "auth.novelia.cc" || method != "GET") return VerificationRoute.BLOCK
    if(mainFrame) return if(parsed.newBuilder().fragment(null).build().toString() == pageUrl)
        VerificationRoute.APP else VerificationRoute.BLOCK
    val path = parsed.encodedPath
    return if(path.startsWith("/assets/") && listOf(".js", ".css", ".woff", ".woff2", ".svg").any(path::endsWith))
        VerificationRoute.APP else VerificationRoute.BLOCK
}

/** Operates only on the host page; the Cloudflare iframe and browser APIs remain untouched. */
internal val verificationSnapshotScript = """
    (() => {
      if (location.origin !== 'https://auth.novelia.cc') return { ready: false };
      if (document.body && document.body.textContent.includes('人机验证加载失败')) return { failed: true };
      const widget = document.querySelector('form [aria-busy]');
      if (!widget || !widget.querySelector('.leading-0')) return { ready: false };
      // Keep the upstream widget and its callbacks alive, hiding the duplicate account form.
      for (let node = widget; node && node !== document.body; node = node.parentElement) {
        const parent = node.parentElement;
        if (!parent) break;
        for (const sibling of parent.children) {
          if (sibling !== node) sibling.style.setProperty('display', 'none', 'important');
        }
        for (const [key, value] of Object.entries({ display: 'block', margin: '0', padding: '0',
            minHeight: '0', height: 'auto', maxWidth: 'none', width: '100%' })) parent.style[key] = value;
      }
      document.body.style.background = 'transparent';
      let token = '';
      try {
        if (window.turnstile) {
          if (window.turnstile.isExpired()) return { ready: true, expired: true };
          token = window.turnstile.getResponse();
        }
      } catch (_) {}
      return { ready: true, token: typeof token === 'string' && token.length <= 2048 ? token : '' };
    })()
""".trimIndent()
