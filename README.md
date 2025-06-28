# Feederism

**Feederism** is an experimental Android app designed to monitor and parse updates from selected websites — especially event-driven, gallery-based, or calendar-embedded content — and deliver them as readable feed entries with precise time parsing and notification support.

📡 Built with Kotlin, Jetpack Compose, and modern Android architecture components, **Feederism** is a lightweight but powerful tool to "feed" the user timely information from semi-structured or unstructured web content.

---

## ✨ Features

- 🕰️ **Smart Date Extraction**  
  Extracts date/time info from HTML pages using JSoup + regex heuristics (e.g., "April 5 at 6:30pm", "2025-06-21 至 2025-07-15", etc.)

- 📬 **Feed Reader Compatibility**  
  Integrates with RSS-like structures or even non-RSS pages via custom site parsers.

- 📆 **Event Structuring**  
  Supports exhibition periods, openings, closings, deadlines, and multiple event segments under one article.

- ⚙️ **Custom Parsers & Filters**  
  Easily modify or extend `SitemapParser` and `EventSegmentExtractor` to target specific domains (e.g., `howlarts.org`, `smg.sh`).

- 📱 **Android-Native UI**  
  Built with Jetpack Compose, Material 3, and navigation components for a clean, responsive user experience.

---

## 🔧 Tech Stack

- **Kotlin + Coroutines**  
- **Jetpack Compose**  
- **Android Lifecycle + ViewModel**  
- **Jsoup** for HTML parsing  
- **Gradle Kotlin DSL**

---

## 🚧 Roadmap

- [ ] Auto-sync + background fetching
- [ ] Markdown rendering in article body
- [ ] Notification on new updates
- [ ] Local calendar integration (.ics)
- [ ] UI/UX refinement for tablet layout

---

## 📁 Folder Structure

