# AniHome wallpapers

The landscape originals are 3840 × 2160 JPEGs. The new collections use 3840 × 3840 square masters. A centered 16:9 or 9:16 crop from a square master produces a 4K UHD image in the selected orientation.

The artwork was generated with ImageGen and resampled to the listed delivery dimensions.

## Original review collection

- [Midnight Glass](originals/midnight-glass.jpg)
- [Indigo Orbit](originals/indigo-orbit.jpg)
- [Ember Dunes](originals/ember-dunes.jpg)
- [Jade Fog](originals/jade-fog.jpg)
- [Rainy Tokyo](originals/rainy-tokyo.jpg)
- [Moonlit Coast](originals/moonlit-coast.jpg)
- [Frosted Pearl](originals/frosted-pearl.jpg)
- [Sakura Haze](originals/sakura-haze.jpg)
- [Alpine Air](originals/alpine-air.jpg)
- [Violet Silk](originals/violet-silk.jpg)

## New wallpapers

- [Basalt And Mist](collection/basalt-and-mist.jpg)
- [Blue Hour Lake](collection/blue-hour-lake.jpg)
- [Amber Acacia](collection/amber-acacia.jpg)
- [Paper Folds](collection/paper-folds.jpg)
- [Cobalt Kelp](collection/cobalt-kelp.jpg)
- [Rain On Slate](collection/rain-on-slate.jpg)
- [Lavender Lake](collection/lavender-lake.jpg)
- [Orange Peel Sunrise](collection/orange-peel-sunrise.jpg)
- [Moss Stone](collection/moss-stone.jpg)
- [Silver Cloudbank](collection/silver-cloudbank.jpg)
- [Plum Ceramic](collection/plum-ceramic.jpg)
- [Sandstone Arch](collection/sandstone-arch.jpg)
- [Arctic Ice](collection/arctic-ice.jpg)
- [Storm Over Grass](collection/storm-over-grass.jpg)
- [Olive Linen](collection/olive-linen.jpg)
- [Red Moon Fog](collection/red-moon-fog.jpg)
- [Copper Patina](collection/copper-patina.jpg)
- [Terrace Rain](collection/terrace-rain.jpg)
- [Glacier Pass](collection/glacier-pass.jpg)
- [Ink Bloom](collection/ink-bloom.jpg)

## Illustrated cities

- [New York City, United States](cities/new-york.jpg)
- [London, United Kingdom](cities/london.jpg)
- [Paris, France](cities/paris.jpg)
- [Tokyo, Japan](cities/tokyo.jpg)
- [Delhi, India](cities/delhi.jpg)
- [Beijing, China](cities/beijing.jpg)
- [Seoul, South Korea](cities/seoul.jpg)
- [Sydney, Australia](cities/sydney.jpg)
- [Rio de Janeiro, Brazil](cities/rio-de-janeiro.jpg)
- [Cairo, Egypt](cities/cairo.jpg)
- [Rome, Italy](cities/rome.jpg)
- [Berlin, Germany](cities/berlin.jpg)
- [Dubai, United Arab Emirates](cities/dubai.jpg)
- [Istanbul, Türkiye](cities/istanbul.jpg)
- [Toronto, Canada](cities/toronto.jpg)
- [Singapore](cities/singapore.jpg)
- [Mexico City, Mexico](cities/mexico-city.jpg)
- [Cape Town, South Africa](cities/cape-town.jpg)
- [Bangkok, Thailand](cities/bangkok.jpg)
- [Barcelona, Spain](cities/barcelona.jpg)

## India collection

- [Mumbai, India](india/mumbai.jpg)
- [Jaipur, India](india/jaipur.jpg)
- [Varanasi, India](india/varanasi.jpg)
- [Ladakh, India](india/ladakh.jpg)
- [Kerala Backwaters, India](india/kerala-backwaters.jpg)
- [Meghalaya, India](india/meghalaya.jpg)

## Adding wallpapers and categories

AniHome reads this folder straight from AniProject's `main` branch every time its Wallpaper screen opens. Pushing to GitHub is all it takes: there is no catalog to update and no app release needed.

**A category is a folder** directly inside `banners/wallpapers/`. Every folder becomes a category chip in the app (with an "All" chip in front), labelled from the folder name:

| Folder | Shown as |
| --- | --- |
| `cities/` | Cities |
| `night-sky/` | Night Sky |
| `02-anime/` | Anime |

- Use lowercase letters, digits and hyphens; hyphens become spaces and each word is capitalised.
- A leading number (`01-`, `02-`) is hidden from the label and sets the order. Otherwise categories are listed alphabetically, after `originals`, which always comes first.
- Keep one level only: an image in `cities/europe/` is listed under Cities.
- Images placed directly in `banners/wallpapers/` (not in a folder) appear under "More".

**A wallpaper is an image file** inside a category folder:

- `.jpg`, `.jpeg`, `.png` or `.webp`, at most 12 MB (aim for 1–3 MB).
- File names use letters, digits, `-`, `_` and `.` only, and start with a letter or digit. The name becomes the title: `kerala-backwaters.jpg` → "Kerala Backwaters". A leading number is hidden and sets the order within the category.
- Size for both orientations: a 3840 × 3840 square master works best, because people frame portrait and landscape separately in the app. A 3840 × 2160 landscape image also works, but portrait framing then shows only its middle.
- Keep the subject reasonably central, so both orientations crop well.

Anything else in this folder (this README, notes, licences) is ignored by the app. To remove a wallpaper or a category, delete the file or folder; it disappears from the app the next time the Wallpaper screen opens. Wallpapers already applied on a device are not affected.
