# Spear Mace Swap (Fabric, Minecraft 26.2)

Client-Mod mit Auto-Combo: Windladung -> Speer -> Mace.

## Benutzen

1. Lege **Windladungen, Speer und Mace** in die Hotbar (Slots 1-9).
2. Schau ungefaehr auf dein Ziel (bis 40 Bloecke, Winkel max. 20 Grad).
3. Druecke **R**. Die Mod sperrt das Ziel und startet:
   - Zu tief? Sie wirft Windladungen auf den Boden unter dir und katapultiert dich hoch.
   - Ziel noch weit weg? Sie sticht immer wieder mit dem Speer zu (Lunge zieht dich nach vorne).
   - Nah dran, ueber dem Ziel und im Fallen? Sie wechselt auf die Mace und schlaegt zu. Danach stoppt sie.
4. R noch einmal druecken bricht jederzeit ab.

Die Taste aenderst du unter Optionen > Steuerung > Tastenbelegung > "Spear Mace Swap".

Tipps: Der Speer sollte Lunge haben, und du brauchst genug Hunger (Lunge braucht mehr als 6 Hungerpunkte).

## Einstellungen

Oben in `SpearMaceClient.java` stehen Konstanten (Reichweite, Hoehe, Zeit zwischen Windladungen und Stichen).
Wenn die Combo zu frueh oder zu spaet zuschlaegt, passe dort `CLOSE_HORIZONTAL`, `MACE_REACH` und
`WANTED_HEIGHT_ABOVE` an und baue die Mod neu.

## Bauen

Voraussetzung: JDK 25 und Gradle 9.5.1.

```
gradle wrapper --gradle-version 9.5.1
./gradlew build
```

Die fertige Mod liegt in `build/libs/spear-mace-1.0.0.jar` (die Datei ohne `-sources`).
In den `mods`-Ordner von Minecraft kopieren. Du brauchst ausserdem Fabric Loader 0.19.3+ und
Fabric API 0.161.0+26.2.

## Wichtig

- Die Mod zielt selbst (sie dreht deinen Blick auf das Ziel) und steuert Items. Auf Multiplayer-Servern gilt das
  fast ueberall als Cheat und fuehrt zum Bann. Nutze sie nur im Singleplayer, auf eigenen Servern oder dort,
  wo es ausdruecklich erlaubt ist.
- Attribute Swapping ist ein Vanilla-Bug (MC-28289) und soll in 26.3 entfernt werden.
- Der Code wurde nicht im Spiel getestet. Die Werte (Zeiten, Hoehen) brauchen vermutlich etwas Feinabstimmung.
