package app.anihome.launcher.web

import app.anihome.launcher.tracking.PageSnapshot
import org.json.JSONObject

/**
 * JavaScript evaluated inside the open page to read what the user is watching.
 * It only reads the DOM of the page already on screen and returns a small JSON object;
 * it never contacts anything.
 *
 * Selectors are verified against supported sites:
 * - Crunchyroll: document title / E-prefix heading, `[data-t=show-title]`, series links
 * - HiAnime / Zoro family: `.film-name`, `.ssl-item.ep-item.active` with `data-number`
 * - AniKoto: `h1.title.d-title`, `[data-ep-name]`, `#report-episode`, URL `/ep-N`
 */
object PageProbe {
    val SCRIPT = """
        (function () {
          function text(el) { return el && (el.textContent || '').trim() || null; }
          function num(v) { var n = parseInt(String(v || '').replace(/[^0-9]/g, ''), 10); return isNaN(n) ? null : n; }
          var h1 = document.querySelector(
            'h1.title.d-title, h1[itemprop="name"], h1, .film-name, .anime-title, .title h2, ' +
            '[class*="series-title"], [class*="show-title"], [data-t="title"]'
          );
          var active = document.querySelector(
            '.ep-item.active, .ssl-item.active, .ssl-item.ep-item.active, .ep-item.ep-active, ' +
            '.episode.active, .episodes .active, a.active[data-number], a.ep-item.active, ' +
            '[class*="episode"][class*="active"], [class*="ep-"][class*="active"], li.active[data-episode], ' +
            '[aria-current="page"][data-number], [aria-current="true"][data-number], .selected[data-number], ' +
            '.playing[data-number], .ss-list a.active'
          );
          var domEp = null;
          if (active) {
            domEp = num(active.getAttribute('data-number')) || num(active.getAttribute('data-episode')) || num(active.getAttribute('data-ep'))
              || num(active.getAttribute('data-episode-number')) || num(active.getAttribute('title')) || num(text(active));
          }
          if (domEp === null) {
            var wrap = document.querySelector('[data-ep-name], [data-ep-number], [data-episode-number]');
            if (wrap) domEp = num(wrap.getAttribute('data-ep-name')) || num(wrap.getAttribute('data-ep-number')) || num(wrap.getAttribute('data-episode-number'));
          }
          if (domEp === null) {
            var crumb = document.querySelector('#report-episode, [data-testid="episode-number"], .episode-number, .current-episode, [class*="current-ep"]');
            if (crumb) domEp = num(text(crumb));
          }
          if (domEp === null) {
            var epLabel = document.querySelector('.episode span, .name .episode');
            if (epLabel) domEp = num(text(epLabel));
          }
          var domTitle = null;
          var titleEl = document.querySelector(
            'h1.title.d-title, h1[itemprop="name"], [data-testid="series-title"], [data-t="show-title"], ' +
            '.film-name a, h2.film-name, .anisc-detail .film-name, .anis-watch-detail .film-name, ' +
            '.anime-title a, a[class*="series-title"], [class*="show-title"] a, a[href*="/series/"]'
          );
          if (titleEl) domTitle = text(titleEl);
          var best = null;
          var videos = document.querySelectorAll('video');
          for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            if (!best || (v.duration || 0) > (best.duration || 0)) best = v;
          }
          var out = {
            url: location.href,
            title: document.title || null,
            heading: text(h1),
            domEpisode: domEp,
            domTitle: domTitle,
            pos: best && isFinite(best.currentTime) ? Math.floor(best.currentTime) : 0,
            dur: best && isFinite(best.duration) ? Math.floor(best.duration) : 0,
            playing: !!(best && !best.paused && !best.ended)
          };
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /** Parses the probe's result; [raw] arrives JSON-string-encoded from evaluateJavascript. */
    fun parse(raw: String?, fallbackUrl: String, fallbackTitle: String?): PageSnapshot? {
        if (raw == null || raw == "null") return PageSnapshot(fallbackUrl, fallbackTitle)
        val decoded = runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrNull() ?: return PageSnapshot(fallbackUrl, fallbackTitle)
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return PageSnapshot(fallbackUrl, fallbackTitle)
        return PageSnapshot(
            url = json.optString("url").ifBlank { fallbackUrl },
            title = json.optString("title").ifBlank { null } ?: fallbackTitle,
            heading = json.optString("heading").ifBlank { null },
            domEpisode = if (json.isNull("domEpisode")) null else json.optInt("domEpisode").takeIf { it > 0 },
            domTitle = json.optString("domTitle").ifBlank { null },
            videoPositionSec = json.optInt("pos"),
            videoDurationSec = json.optInt("dur"),
            videoPlaying = json.optBoolean("playing"),
        )
    }
}
