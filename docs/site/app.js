// AniProject's shared site interactions.
(() => {
  const repo = "akasumitlamba/AniProject";
  const raw = `https://raw.githubusercontent.com/${repo}/main/banners/`;
  const motion = matchMedia("(prefers-reduced-motion: reduce)");
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  async function json(url) {
    const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }

  async function latestRelease() {
    const key = "aniproject-latest-release";
    try {
      const cached = JSON.parse(sessionStorage.getItem(key));
      if (cached?.expires > Date.now()) return cached.release;
    } catch {
      // Storage can be disabled. Fetch directly in that case.
    }
    const data = await json(`https://api.github.com/repos/${repo}/releases/latest`);
    const patterns = { anihome: /^AniHome-Launcher-.*\.apk$/, anibrave: /^AniBrave-.*arm64.*\.apk$/ };
    const release = { version: data.tag_name, downloads: {} };
    for (const [product, pattern] of Object.entries(patterns)) {
      const asset = data.assets.find(item => pattern.test(item.name));
      if (asset?.browser_download_url.startsWith(`https://github.com/${repo}/releases/download/`)) {
        release.downloads[product] = asset.browser_download_url;
      }
    }
    try {
      sessionStorage.setItem(key, JSON.stringify({ release, expires: Date.now() + 300_000 }));
    } catch {
      // The resolved links still work without storage.
    }
    return release;
  }

  const downloads = $$("[data-download]");
  if (downloads.length) {
    latestRelease().then(release => {
      for (const link of downloads) {
        const url = release.downloads[link.dataset.download];
        if (url) {
          link.href = url;
          $("[data-version]", link).textContent = release.version;
        }
      }
    }).catch(() => {
      // The HTML links go to the latest release when a direct asset can't be resolved.
    });
  }

  const nav = $(".nav");
  let pendingScroll = false;
  function updateScroll() {
    nav.classList.toggle("scrolled", scrollY > 8);
    pendingScroll = false;
  }
  addEventListener("scroll", () => {
    if (!pendingScroll) {
      pendingScroll = true;
      requestAnimationFrame(updateScroll);
    }
  }, { passive: true });
  if ("IntersectionObserver" in window) {
    let initial = true;
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        observer.unobserve(entry.target);
        if (!initial && !motion.matches) entry.target.classList.add("revealed");
      }
      initial = false;
    });
    for (const section of $$(".channel-head, .wallpaper > div, .install-head, .get > h2")) {
      observer.observe(section);
    }
  }

  const crawl = $("[data-crawl]");
  if (crawl) {
    if ("IntersectionObserver" in window) {
      const observer = new IntersectionObserver(([entry]) => {
        crawl.classList.toggle("in-view", entry.isIntersecting);
      });
      observer.observe(crawl);
    } else {
      crawl.classList.add("in-view");
    }
    const toggle = $("[data-motion]", crawl);
    toggle.addEventListener("click", () => {
      const paused = crawl.classList.toggle("paused");
      toggle.textContent = paused ? "Play banner crawl" : "Pause banner crawl";
    });
  }

  const tuner = $("[data-tuner]");
  if (tuner) {
    const tabs = $$("[role=tab]", tuner);
    function select(index) {
      tabs.forEach((tab, position) => {
        const selected = position === index;
        tab.setAttribute("aria-selected", selected);
        tab.tabIndex = selected ? 0 : -1;
        document.getElementById(tab.getAttribute("aria-controls")).hidden = !selected;
      });
    }
    tabs.forEach((tab, index) => {
      tab.addEventListener("click", () => select(index));
      tab.addEventListener("keydown", event => {
        const next = {
          ArrowDown: (index + 1) % tabs.length,
          ArrowRight: (index + 1) % tabs.length,
          ArrowUp: (index + tabs.length - 1) % tabs.length,
          ArrowLeft: (index + tabs.length - 1) % tabs.length,
          Home: 0,
          End: tabs.length - 1,
        }[event.key];
        if (next === undefined) return;
        event.preventDefault();
        select(next);
        tabs[next].focus();
      });
    });
  }

  const wall = $("[data-wall]");
  if (!wall) return;

  const chips = $("[data-chips]");
  const search = $("[data-search]");
  const status = $("[data-status]");
  const more = $("[data-more]");
  const categoryLabel = $("[data-category-label]");
  const localCatalogue = "site/catalogue/";
  const localArtwork = new Map();
  const pageSize = 72;
  let entries = [];
  let category = "all";
  let shown = pageSize;

  const label = name => name.charAt(0).toUpperCase() + name.slice(1);
  const normalize = name => name.toLowerCase().normalize("NFKD").replace(/[^\p{L}\p{N}]+/gu, "");
  function tile(entry) {
    const item = document.createElement("li");
    item.className = "tile";
    item.innerHTML = '<div class="art"><img alt="" width="162" height="100" loading="lazy" decoding="async"></div><div class="cap"><span class="name"></span><span class="no"></span></div>';
    const image = $("img", item);
    const thumbnail = localCatalogue + entry.file.replace(/\.[^.]+$/, ".webp");
    const remote = raw + entry.file;
    const hasLocalImage = localArtwork.has(entry.file);
    const useLocal = hasLocalImage && localArtwork.get(entry.file) === entry.sha256;
    image.src = useLocal ? thumbnail : remote;
    if (hasLocalImage) {
      image.addEventListener("error", () => { image.src = useLocal ? remote : thumbnail; }, { once: true });
    }
    $(".name", item).textContent = entry.name;
    $(".no", item).textContent = "No. " + String(entry.number).padStart(3, "0");
    return item;
  }

  function render(reset) {
    const query = search.value.trim();
    const normalized = normalize(query);
    const matching = entries.filter(entry =>
      (category === "all" || entry.category === category) &&
      (!normalized || entry.searchTerms.some(term => term.includes(normalized))));
    if (reset) {
      wall.replaceChildren();
      shown = pageSize;
    }
    wall.append(...matching.slice(wall.children.length, shown).map(tile));
    const hideMore = matching.length <= shown;
    if (hideMore && document.activeElement === more) status.focus({ preventScroll: true });
    more.hidden = hideMore;
    status.textContent = matching.length
      ? `${matching.length} banner${matching.length === 1 ? "" : "s"}${category === "all" ? "" : " in " + label(category)}${query ? ` matching “${query}”` : ""}`
      : query ? `No banners match “${query}”.` : "No banners in this category.";
  }

  function buildChips() {
    chips.replaceChildren();
    const counts = new Map();
    for (const entry of entries) counts.set(entry.category, (counts.get(entry.category) || 0) + 1);
    const categories = [...counts].sort((a, b) => b[1] - a[1]);
    if (category !== "all" && !counts.has(category)) category = "all";
    categoryLabel.textContent = category === "all" ? "Categories" : `Category: ${label(category)}`;
    for (const [key, count] of [["all", entries.length], ...categories]) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "chip";
      button.dataset.category = key;
      button.textContent = label(key);
      button.setAttribute("aria-pressed", key === category);
      const number = document.createElement("span");
      number.className = "n";
      number.textContent = count;
      button.append(" ", number);
      button.addEventListener("click", () => {
        category = key;
        categoryLabel.textContent = key === "all" ? "Categories" : `Category: ${label(key)}`;
        for (const chip of chips.children) chip.setAttribute("aria-pressed", chip === button);
        render(true);
      });
      chips.append(button);
    }
  }

  let typing;
  search.addEventListener("input", () => {
    clearTimeout(typing);
    typing = setTimeout(() => render(true), 120);
  });
  more.addEventListener("click", () => {
    shown += pageSize;
    render(false);
  });

  function showCatalogue(catalogue) {
    entries = catalogue.map((entry, index) => ({
      ...entry,
      number: index + 1,
      searchTerms: [entry.name, ...(entry.aliases || [])].map(normalize),
    })).sort((a, b) => (b.tabletPriority || 0) - (a.tabletPriority || 0) || a.name.localeCompare(b.name));
    $("[data-count]").textContent = entries.length;
    search.disabled = false;
    buildChips();
    render(true);
    wall.removeAttribute("data-loading");
  }

  async function loadCatalogue() {
    const live = json(raw + "catalog.json").catch(() => null);
    const bundled = await json(localCatalogue + "catalog.json").catch(() => null);
    if (bundled?.entries.length) {
      for (const entry of bundled.entries) localArtwork.set(entry.file, entry.sha256);
      showCatalogue(bundled.entries);
    }
    const current = await live;
    if (current?.entries.length) {
      const fields = ["file", "sha256", "name", "category", "tabletPriority", "aliases"];
      const key = catalogue => JSON.stringify(catalogue.map(entry => fields.map(field => entry[field])));
      if (!bundled?.entries.length || key(current.entries) !== key(bundled.entries)) showCatalogue(current.entries);
    } else if (!bundled?.entries.length) {
      throw new Error("No banner catalogue available");
    }
  }

  loadCatalogue().catch(() => {
    const link = document.createElement("a");
    link.href = `https://github.com/${repo}/tree/main/banners`;
    link.textContent = "Browse it on GitHub";
    status.replaceChildren("The catalogue couldn’t load. ", link, ".");
  }).finally(() => wall.removeAttribute("data-loading"));
})();
