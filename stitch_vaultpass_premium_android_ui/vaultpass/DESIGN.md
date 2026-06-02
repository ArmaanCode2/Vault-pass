---
name: VaultPass
colors:
  surface: '#08132a'
  surface-dim: '#08132a'
  surface-bright: '#2f3952'
  surface-container-lowest: '#030d25'
  surface-container-low: '#101b33'
  surface-container: '#151f37'
  surface-container-high: '#1f2942'
  surface-container-highest: '#2a344d'
  on-surface: '#d9e2ff'
  on-surface-variant: '#bacac3'
  inverse-surface: '#d9e2ff'
  inverse-on-surface: '#263049'
  outline: '#85948e'
  outline-variant: '#3c4a45'
  surface-tint: '#38debb'
  primary: '#ffffff'
  on-primary: '#00382d'
  primary-container: '#5ffbd6'
  on-primary-container: '#00725e'
  inverse-primary: '#006b58'
  secondary: '#b9c7e4'
  on-secondary: '#233148'
  secondary-container: '#3c4962'
  on-secondary-container: '#abb9d6'
  tertiary: '#ffffff'
  on-tertiary: '#20304f'
  tertiary-container: '#d8e2ff'
  on-tertiary-container: '#556486'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#5ffbd6'
  primary-fixed-dim: '#38debb'
  on-primary-fixed: '#002019'
  on-primary-fixed-variant: '#005142'
  secondary-fixed: '#d6e3ff'
  secondary-fixed-dim: '#b9c7e4'
  on-secondary-fixed: '#0d1c32'
  on-secondary-fixed-variant: '#39475f'
  tertiary-fixed: '#d8e2ff'
  tertiary-fixed-dim: '#b6c6ed'
  on-tertiary-fixed: '#091b39'
  on-tertiary-fixed-variant: '#374767'
  background: '#08132a'
  on-background: '#d9e2ff'
  surface-variant: '#2a344d'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 57px
    fontWeight: '700'
    lineHeight: 64px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  title-lg:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '500'
    lineHeight: 28px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.05em
  code-display:
    fontFamily: JetBrains Mono
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 12px
  md: 16px
  lg: 24px
  xl: 32px
  container-padding: 20px
  gutter: 16px
---

## Brand & Style
The design system is engineered to evoke absolute trust and technical sophistication. It targets high-security users who demand professional-grade tools without the friction of traditional enterprise software.

The visual style is **Corporate / Modern** with subtle **Glassmorphic** accents. It prioritizes clarity and high-end finish through "Deep Tech" aesthetics—utilizing generous whitespace (negative space), precision-engineered components, and a focus on high-readability typography. The atmosphere is calm and authoritative, ensuring users feel their most sensitive data is protected by a premium, intelligent architecture.

## Colors
The palette is rooted in high-contrast "Cyber-Navy" tones to reinforce the security narrative.

- **Primary (Electric Blue):** Used for critical action paths, active states, and high-priority indicators. It provides a luminous contrast against dark backgrounds.
- **Secondary/Tertiary (Deep Navy & Slate):** These form the structural foundation. In dark mode, `#0A192F` serves as the base surface, while `#112240` acts as the container color to create depth.
- **Grayscale:** A premium range of cool grays (from Slate to Silver) is used for secondary text and borders to maintain a sophisticated hierarchy.
- **Functional Colors:** Emerald is reserved strictly for successful encryption and unlocked states; Red is used for security alerts and data deletion.

## Typography
This design system utilizes **Inter** for all UI elements and prose due to its exceptional legibility at small sizes and its neutral, modern tone. 

To emphasize the "technical" nature of a password manager, **JetBrains Mono** is introduced for secondary labels, password displays, and hex codes. This monospaced contrast provides a visual cue that the user is interacting with secure, raw data. 

Hierarchy is established through weight rather than dramatic size shifts. Use `SemiBold` (600) for section headers and `Medium` (500) for interactive labels.

## Layout & Spacing
The layout follows a **Fluid Grid** model optimized for Android's diverse screen sizes, adhering to Material 3's 8dp spacing logic.

- **Mobile:** 4-column grid with 20px outside margins and 16px gutters.
- **Tablet/Desktop:** 12-column grid. On larger displays, the content is centered within a 1200px max-width container to prevent line-lengths from becoming unreadable.
- **Rhythm:** Vertical spacing should strictly follow the 8px scale. Use 24px (lg) between distinct sections and 16px (md) between related components within a card.

## Elevation & Depth
Depth is communicated through **Tonal Layers** supplemented by subtle **Glassmorphism**.

1. **Base (Level 0):** Background color `#0A192F`. No shadow.
2. **Surface (Level 1):** Cards and Sheets using `#112240`. These use a 1px inner stroke of 10% white to define edges.
3. **Overlay (Level 2):** Floating Action Buttons and Dialogs. These use "Ambient Shadows"—deep, ultra-diffused shadows with a 20% opacity of the primary accent color rather than pure black, creating a "glow" effect.
4. **Glass Accents:** For navigation bars and top app bars, use a 70% opacity version of the surface color with a 20px backdrop blur to maintain context of the content scrolling beneath.

## Shapes
The shape language is "Soft-Modern." While the base system uses 8px (0.5rem) for most small components, high-level containers and cards use much larger radii to feel more premium and approachable.

- **Buttons & Small Inputs:** 8px (Soft).
- **Cards & Bottom Sheets:** 24px (rounded-xl) to align with Material 3’s high-radius aesthetic.
- **FAB:** Fully circular (Pill) to ensure it stands out as the primary action.

## Components
- **Buttons:** Primary buttons use a solid Electric Blue fill with dark navy text for maximum contrast. Secondary buttons use an "Outlined" style with a 1.5px border.
- **Cards:** Use the Level 1 Surface color. Remove heavy shadows in favor of the subtle 1px border stroke. For password entries, include a monospaced "hidden" preview.
- **Floating Action Button (FAB):** Positioned in the bottom-right. Use the primary color with a high elevation (Level 2) shadow.
- **Inputs:** Fields should be "Filled" style (Material 3) but with a custom dark background (`#16274B`). The active state is indicated by a 2px primary-colored bottom border and a scaled-up label.
- **Navigation:** Use the Navigation Rail for tablets/landscape and a Navigation Bar (bottom) for mobile. Icons should be "Duotone" security-themed (e.g., shielded locks, biometric fingerprints).
- **Chips:** Used for "Strength Indicators" (Weak, Fair, Strong). These should be low-profile with no borders, using a background tint derived from the status color (e.g., 10% Emerald for a "Strong" chip).