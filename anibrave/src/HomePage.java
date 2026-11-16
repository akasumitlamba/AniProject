package app.anibrave;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * AniBrave's homepage (new tabs): clock, a search box that uses the browser's own default
 * search engine, and the weather for a city the user picks (Open-Meteo: free, no account).
 *
 * It is served by a tiny HTTP server bound to the loopback interface only, so it loads
 * instantly and offline, and as a localhost page it is a secure context that can remember
 * the chosen city. Searches go to Chrome's ACTION_WEB_SEARCH handling, which always uses the
 * default engine the user has chosen in AniBrave's settings.
 */
final class HomePage {
    private static final int[] PORTS = {47613, 47614, 47615, 47616};
    private static volatile int port;
    private static volatile Context app;
    private static final String token = Long.toHexString(new SecureRandom().nextLong());

    private HomePage() {}

    /** The homepage address, starting the server on first use; null if it can't start. */
    static synchronized String url() {
        if (port == 0) start();
        return port == 0 ? null : "http://127.0.0.1:" + port + "/";
    }

    static boolean isHome(String url) {
        return port != 0 && url != null && url.startsWith("http://127.0.0.1:" + port + "/");
    }

    private static void start() {
        try {
            if (app == null) app = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Exception ignored) { /* searches simply won't start */ }
        for (int candidate : PORTS) {
            try {
                ServerSocket server = new ServerSocket(candidate, 16, InetAddress.getLoopbackAddress());
                port = candidate;
                Thread thread = new Thread(() -> serve(server), "AniBrave-home");
                thread.setDaemon(true);
                thread.start();
                return;
            } catch (Exception ignored) { /* port in use: try the next */ }
        }
    }

    private static void serve(ServerSocket server) {
        while (true) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(4000);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String request = in.readLine();
                String host = "";
                for (String line; (line = in.readLine()) != null && !line.isEmpty(); )
                    if (line.regionMatches(true, 0, "Host:", 0, 5)) host = line.substring(5).trim();
                OutputStream out = socket.getOutputStream();
                // Only this exact loopback origin (blocks DNS-rebinding pages from using it).
                if (request == null || !host.equals("127.0.0.1:" + port)) { respond(out, "403 Forbidden", "text/plain", "", ""); continue; }
                String[] parts = request.split(" ");
                String path = parts.length > 1 ? parts[1] : "/";
                if (path.equals("/") || path.startsWith("/?")) respond(out, "200 OK", "text/html; charset=utf-8", page(), "no-store");
                else if (path.startsWith("/search?")) {
                    Uri uri = Uri.parse("http://localhost" + path);
                    String query = uri.getQueryParameter("q");
                    if (token.equals(uri.getQueryParameter("t")) && query != null && !query.trim().isEmpty()) search(query.trim());
                    respond(out, "204 No Content", "text/plain", "", "no-store");
                } else respond(out, "404 Not Found", "text/plain", "", "");
            } catch (Exception ignored) {
                /* one bad request never stops the homepage */
            }
        }
    }

    private static void respond(OutputStream out, String status, String type, String body, String cache) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + bytes.length
            + "\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer"
            + (cache.isEmpty() ? "" : "\r\nCache-Control: " + cache)
            + "\r\nContent-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self' https://api.open-meteo.com https://geocoding-api.open-meteo.com; img-src data:; form-action 'self'\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    /** The browser's own web-search intent: always the user's default search engine. */
    private static void search(String query) {
        Context context = app;
        if (context == null) return;
        // The browser's intent dispatcher (the search widget activity also accepts WEB_SEARCH,
        // so a package-only intent could show a chooser).
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH)
            .setClassName(context.getPackageName(), "com.google.android.apps.chrome.IntentDispatcher")
            .putExtra(SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        AniRuntime.homeSearch(context, intent);
    }

    private static String page() {
        return PAGE.replace("__TOKEN__", token);
    }

    private static final String PAGE = "<!doctype html><html lang=en><head><meta charset=utf-8>"
        + "<meta name=viewport content='width=device-width,initial-scale=1'><meta name=color-scheme content='light dark'>"
        + "<title>New tab</title><style>"
        + ":root{--bg:#f3f5fa;--card:#ffffffcc;--ink:#18202e;--muted:#5d6779;--line:#d9dfea;--accent:#3c5fb5}"
        + "@media(prefers-color-scheme:dark){:root{--bg:#11141b;--card:#1c212bcc;--ink:#e7ebf3;--muted:#9aa5ba;--line:#2c3340;--accent:#a9c2ff}}"
        + "*{box-sizing:border-box}html,body{margin:0;height:100%}"
        + "body{background:radial-gradient(1200px 600px at 50% -10%,color-mix(in srgb,var(--accent) 18%,transparent),transparent),var(--bg);"
        + "color:var(--ink);font:16px/1.4 system-ui,-apple-system,Roboto,sans-serif;display:flex;align-items:center;justify-content:center;padding:24px}"
        + "main{width:min(640px,100%);display:flex;flex-direction:column;align-items:center;gap:28px;margin-top:-6vh}"
        + "#time{font-size:clamp(56px,11vw,96px);font-weight:300;letter-spacing:-2px;line-height:1}"
        + "#date{color:var(--muted);font-size:18px;margin-top:8px;text-align:center}"
        + "form{width:100%;display:flex;align-items:center;gap:10px;background:var(--card);border:1px solid var(--line);"
        + "border-radius:28px;padding:6px 6px 6px 20px;box-shadow:0 6px 24px #0000001a;backdrop-filter:blur(12px)}"
        + "input{flex:1;min-width:0;border:0;background:transparent;color:var(--ink);font-size:18px;padding:10px 0;outline:none}"
        + "button{border:0;cursor:pointer;font:inherit}"
        + "#go{background:var(--accent);color:var(--bg);border-radius:22px;padding:10px 18px;font-weight:600}"
        + "#weather{display:flex;align-items:center;gap:16px;background:var(--card);border:1px solid var(--line);border-radius:22px;"
        + "padding:14px 20px;min-width:min(360px,100%);backdrop-filter:blur(12px)}"
        + "#icon{font-size:40px;line-height:1}#temp{font-size:34px;font-weight:300}#cond{color:var(--muted);font-size:14px}"
        + "#place{background:none;color:var(--accent);padding:0;font-size:14px;text-align:left}"
        + "#picker{display:none;width:100%;gap:8px}#picker.open{display:flex}"
        + "#picker input{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:10px 14px}"
        + "#picker button{background:var(--card);border:1px solid var(--line);color:var(--ink);border-radius:18px;padding:10px 14px}"
        + "#results{width:100%;display:flex;flex-direction:column;gap:6px}"
        + "#results button{background:var(--card);border:1px solid var(--line);color:var(--ink);border-radius:14px;padding:10px 14px;text-align:left}"
        + "</style></head><body><main>"
        + "<div><div id=time>--:--</div><div id=date></div></div>"
        + "<form id=search action=/search autocomplete=off><input id=q name=q placeholder='Search or type a site' aria-label=Search autofocus>"
        + "<button id=go type=submit>Search</button></form>"
        + "<div id=weather><div id=icon aria-hidden=true>☀</div><div><div id=temp>--°</div><div id=cond>Weather</div>"
        + "<button id=place type=button>Choose your city</button></div></div>"
        + "<div id=picker><input id=city placeholder='City name' aria-label='City name'><button id=find type=button>Find</button>"
        + "<button id=here type=button>Use my location</button></div><div id=results></div>"
        + "</main><script>"
        + "const T='__TOKEN__';const $=id=>document.getElementById(id);"
        + "function clock(){const d=new Date();$('time').textContent=d.toLocaleTimeString([], {hour:'numeric',minute:'2-digit'});"
        + "$('date').textContent=d.toLocaleDateString([], {weekday:'long',day:'numeric',month:'long'});}clock();setInterval(clock,1000);"
        + "$('search').addEventListener('submit',e=>{e.preventDefault();const q=$('q').value.trim();if(!q)return;"
        + "const u=/^[a-z]+:\\/\\//i.test(q)||/^[^\\s]+\\.[a-z]{2,}(\\/\\S*)?$/i.test(q)?(/^[a-z]+:\\/\\//i.test(q)?q:'https://'+q):null;"
        + "if(u){location.href=u;return;}fetch('/search?t='+T+'&q='+encodeURIComponent(q));});"
        + "const codes=c=>c===0?['☀','Clear']:c<=2?['⛅','Partly cloudy']:c===3?['☁','Overcast']:c<=48?['🌫','Fog']:c<=57?['🌦','Drizzle']"
        + ":c<=67?['🌧','Rain']:c<=77?['❄','Snow']:c<=82?['🌧','Showers']:c<=86?['🌨','Snow showers']:['⛈','Thunderstorm'];"
        + "function save(p){try{localStorage.setItem('place',JSON.stringify(p))}catch(e){}}"
        + "function load(){try{return JSON.parse(localStorage.getItem('place')||'null')}catch(e){return null}}"
        + "async function weather(){const p=load();if(!p){$('cond').textContent='Weather';$('place').textContent='Choose your city';return;}"
        + "$('place').textContent=p.name;try{const r=await fetch('https://api.open-meteo.com/v1/forecast?latitude='+p.lat+'&longitude='+p.lon"
        + "+'&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1');"
        + "const j=await r.json();const [i,t]=codes(j.current.weather_code);$('icon').textContent=i;$('temp').textContent=Math.round(j.current.temperature_2m)+'°';"
        + "$('cond').textContent=t+' · H '+Math.round(j.daily.temperature_2m_max[0])+'° L '+Math.round(j.daily.temperature_2m_min[0])+'°';}"
        + "catch(e){$('cond').textContent='Weather unavailable offline';}}"
        + "$('place').onclick=()=>{$('picker').classList.toggle('open');$('city').focus();};"
        + "$('find').onclick=async()=>{const q=$('city').value.trim();if(!q)return;$('results').textContent='';"
        + "try{const r=await fetch('https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&name='+encodeURIComponent(q));const j=await r.json();"
        + "(j.results||[]).forEach(x=>{const b=document.createElement('button');b.textContent=[x.name,x.admin1,x.country].filter(Boolean).join(', ');"
        + "b.onclick=()=>{save({name:x.name,lat:x.latitude,lon:x.longitude});$('results').textContent='';$('picker').classList.remove('open');weather();};$('results').append(b);});"
        + "if(!(j.results||[]).length)$('results').textContent='No place found';}catch(e){$('results').textContent='Search needs a connection';}};"
        + "$('city').addEventListener('keydown',e=>{if(e.key==='Enter')$('find').click();});"
        + "$('here').onclick=()=>navigator.geolocation&&navigator.geolocation.getCurrentPosition(p=>{save({name:'My location',lat:p.coords.latitude.toFixed(2),lon:p.coords.longitude.toFixed(2)});"
        + "$('picker').classList.remove('open');weather();},()=>{$('results').textContent='Location is off for this page';});"
        + "weather();setInterval(weather,30*60*1000);"
        + "</script></body></html>";
}
