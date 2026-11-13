# Recent files type icons

`app/src/main/res/drawable/file_type_*.xml` are vector icons: a rounded square in the type's
colour with a white Material Symbols glyph (Apache 2.0). They stay sharp at any density and
match AniHome's rounded controls on the dark theme.

| Kind | Resource | Colour |
| --- | --- | --- |
| PDF | `file_type_pdf` | `#E5484D` |
| Document | `file_type_document` | `#3B82F6` |
| Spreadsheet | `file_type_sheet` | `#16A34A` |
| Presentation | `file_type_slides` | `#EA8A0B` |
| Image | `file_type_image` | `#0EA5A4` |
| Video | `file_type_video` | `#7C5CF6` |
| Audio | `file_type_audio` | `#DB2777` |
| Android package | `file_type_app` | `#3DA35D` |
| Archive | `file_type_archive` | `#B7791F` |
| Text | `file_type_text` | `#5B6B82` |
| Other | `file_type_file` | `#6B7280` |

`RecentFileIcons` maps `RecentFileKind` to these resources.
