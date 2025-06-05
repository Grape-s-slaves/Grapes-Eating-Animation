# 🍇 Grape's Eating Animation (GEA)

**GEA** is a tiny Minecraft mod that lets you customize item "eating" animations by swapping item textures—kind of like how bow pulling works in vanilla. It doesn't change gameplay or add new mechanics—just visuals.

## 🔧 What It Does

By default, Minecraft shows a simple eating animation. With GEA, you can make that animation show a **sequence of custom textures**, defined per item. All you need is a **JSON config** and a **resource pack** with your textures.

GEA doesn't do anything on its own. You need to provide the animation data and textures.

---

## 📦 How to Use

1. **Create a config file at:**
   ```
   config/gea-animations.json
   ```

2. **Add item animation data like this:**
   ```json
   {
     "minecraft:apple": ["minecraft:apple", "minecraft:apple", "gea:apple_0", "gea:apple_1", "gea:apple_2"],
     "minecraft:cookie": ["minecraft:cookie", "minecraft:cookie", "gea:cookie_0", "gea:cookie_1", "gea:cookie_2"]
   }
   ```

   - First **two entries** must be the original item texture (used at the start of eating).
   - The rest are **custom frames** from your **resource pack**.
   - You can use **any namespace** (not just `minecraft:`), so it works with modded items too.

3. **Make sure your resource pack contains matching textures** (e.g., `textures/item/apple_0.png`, etc.).

---

## ✅ Example Setup

In your resource pack:

```
assets/gea/textures/item/apple_0.png
assets/gea/textures/item/apple_1.png
assets/gea/textures/item/apple_2.png
```

And in your `gea-animations.json`:

```json
{
  "minecraft:apple": ["minecraft:apple", "minecraft:apple", "gea:apple_0", "gea:apple_1", "gea:apple_2"]
}
```

---

## 🔍 Useful Commands

| Command | What It Does |
|--------|---------------|
| `/gea animations` | Lists all loaded animations. |
| `/gea get-loaded` | Gives you all items that have custom animations. |
| `/gea reload` | Reloads the animations from the JSON file. |
| `/gea info <player>` | Shows animation info for a specific player. |

---

## 💡 Notes

- Works with **any item from any mod** — just define the full item ID and your custom textures.
- Doesn't change how eating works, only how it **looks**.
- Requires only a JSON config and a compatible resource pack.