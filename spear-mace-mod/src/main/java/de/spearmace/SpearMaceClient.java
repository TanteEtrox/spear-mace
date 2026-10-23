package de.spearmace;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Auto-Combo: Windladung -> Speer -> Mace.
 *
 * Ablauf:
 *   1. Taste (Standard R) druecken, waehrend du ungefaehr auf ein Ziel schaust.
 *      Die Mod sperrt dieses Ziel (Lock-on).
 *   2. Solange du zu tief bist, wirft die Mod Windladungen auf den Boden unter dir
 *      und katapultiert dich nach oben.
 *   3. Ist das Ziel noch weit weg, sticht sie immer wieder mit dem Speer zu
 *      (Lunge bringt dich nach vorne).
 *   4. Bist du ueber dem Ziel, nah dran und faellst, wechselt sie auf die Mace und
 *      schlaegt zu. Danach stoppt die Automatik.
 *
 * Die Taste noch einmal druecken bricht jederzeit ab.
 */
public class SpearMaceClient implements ClientModInitializer {

	private static final String MOD_ID = "spearmace";

	// ---- Einstellungen (nach Geschmack aendern) ----
	/** Maximale Entfernung, in der ein Ziel beim Start gesperrt wird. */
	private static final double MAX_TARGET_DISTANCE = 40.0;
	/** Ziel muss beim Start ungefaehr in Blickrichtung liegen (Winkel in Grad). */
	private static final double LOCK_ANGLE_DEGREES = 20.0;
	/** Ab dieser Entfernung zum Ziel gibt die Mod auf. */
	private static final double GIVE_UP_DISTANCE = 70.0;
	/** Reichweite fuer den Mace-Schlag. */
	private static final double MACE_REACH = 4.5;
	/** Ab dieser waagerechten Entfernung gilt das Ziel als "nah". */
	private static final double CLOSE_HORIZONTAL = 4.0;
	/** So viele Bloecke ueber dem Ziel willst du fliegen. */
	private static final double WANTED_HEIGHT_ABOVE = 4.0;
	/** Ticks zwischen zwei Windladungen (20 Ticks = 1 Sekunde). */
	private static final int BOOST_INTERVAL = 10;
	/** Ticks zwischen zwei Speer-Stichen. */
	private static final int SPEAR_INTERVAL = 12;
	/** Fallgeschwindigkeit, ab der die Mod von "faellt" ausgeht. */
	private static final double FALLING_SPEED = -0.3;

	private static KeyMapping toggleKey;
	private static Method startAttackMethod;
	private static boolean startAttackLookedUp = false;

	private static boolean active = false;
	private static LivingEntity target = null;
	private static int boostCooldown = 0;
	private static int spearCooldown = 0;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(MOD_ID, "main"));

		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.spearmace.combo",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(SpearMaceClient::tick);
	}

	// ------------------------------------------------------------------
	// Tick-Schleife
	// ------------------------------------------------------------------

	private static void tick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			if (active) {
				stop(mc, "Auto-Combo gestoppt.");
			} else {
				start(mc);
			}
		}
		if (!active) {
			return;
		}

		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null || mc.level == null) {
			active = false;
			target = null;
			return;
		}
		if (isScreenOpen(mc)) {
			return; // Menue offen: pausieren
		}
		if (target == null || !target.isAlive() || target.isRemoved()) {
			stop(mc, "Ziel besiegt oder verschwunden.");
			return;
		}
		if (player.distanceTo(target) > GIVE_UP_DISTANCE) {
			stop(mc, "Ziel zu weit weg.");
			return;
		}

		int windSlot = findHotbarSlot(player, s -> s.is(Items.WIND_CHARGE));
		int spearSlot = findHotbarSlot(player, SpearMaceClient::isSpear);
		int maceSlot = findHotbarSlot(player, s -> s.is(Items.MACE));
		if (windSlot < 0 || spearSlot < 0 || maceSlot < 0) {
			stop(mc, "Du brauchst Windladung, Speer und Mace in der Hotbar.");
			return;
		}

		if (boostCooldown > 0) {
			boostCooldown--;
		}
		if (spearCooldown > 0) {
			spearCooldown--;
		}

		// Immer auf das Ziel schauen
		aimAt(player, targetCenter(target));

		double dx = target.getX() - player.getX();
		double dz = target.getZ() - player.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double heightAbove = player.getY() - target.getY();
		boolean falling = player.getDeltaMovement().y < FALLING_SPEED;

		// 1) Nah, ueber dem Ziel und im Fallen -> Mace-Schlag
		if (horizontal <= CLOSE_HORIZONTAL && player.distanceTo(target) <= MACE_REACH && falling) {
			player.getInventory().setSelectedSlot(maceSlot);
			mc.gameMode.attack(player, target);
			player.swing(InteractionHand.MAIN_HAND);
			stop(mc, "Mace-Schlag ausgefuehrt.");
			return;
		}

		// 2) Zu tief -> Windladung auf den Boden werfen
		if (heightAbove < WANTED_HEIGHT_ABOVE && boostCooldown <= 0) {
			float pitchBefore = player.getXRot();
			player.getInventory().setSelectedSlot(windSlot);
			player.setXRot(90.0f); // gerade nach unten
			mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
			player.setXRot(pitchBefore);
			boostCooldown = BOOST_INTERVAL;
			return;
		}

		// 3) Noch weit weg -> mit dem Speer zustechen (Lunge)
		if (horizontal > CLOSE_HORIZONTAL && spearCooldown <= 0) {
			player.getInventory().setSelectedSlot(spearSlot);
			jab(mc, player);
			spearCooldown = SPEAR_INTERVAL;
		}
	}

	// ------------------------------------------------------------------
	// Start / Stop
	// ------------------------------------------------------------------

	private static void start(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || isScreenOpen(mc)) {
			return;
		}

		LivingEntity best = null;
		double bestDot = Math.cos(Math.toRadians(LOCK_ANGLE_DEGREES));
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();

		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity == player || !(entity instanceof LivingEntity living) || !living.isAlive()) {
				continue;
			}
			if (player.distanceTo(living) > MAX_TARGET_DISTANCE) {
				continue;
			}
			Vec3 dir = targetCenter(living).subtract(eye).normalize();
			double dot = dir.dot(look);
			if (dot > bestDot) {
				bestDot = dot;
				best = living;
			}
		}

		if (best == null) {
			player.sendSystemMessage(Component.literal("[SpearMace] Kein Ziel in Blickrichtung gefunden."));
			return;
		}

		target = best;
		boostCooldown = 0;
		spearCooldown = 0;
		active = true;
		player.sendSystemMessage(Component.literal("[SpearMace] Auto-Combo gestartet (zum Abbrechen Taste nochmal druecken)."));
	}

	private static void stop(Minecraft mc, String reason) {
		active = false;
		target = null;
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[SpearMace] " + reason));
		}
	}

	// ------------------------------------------------------------------
	// Hilfsmethoden
	// ------------------------------------------------------------------

	/**
	 * Ist gerade ein Menue (Inventar, Pause, Chat ...) offen? Das Feld "screen" ist in
	 * 26.2 nicht mehr oeffentlich, deshalb wird es per Reflection gelesen.
	 */
	private static Field screenField;
	private static Method screenGetter;
	private static boolean screenLookedUp = false;

	private static boolean isScreenOpen(Minecraft mc) {
		if (!screenLookedUp) {
			screenLookedUp = true;
			try {
				screenField = Minecraft.class.getDeclaredField("screen");
				screenField.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException e) {
				screenField = null;
				for (String name : new String[] { "getScreen", "screen" }) {
					try {
						screenGetter = Minecraft.class.getDeclaredMethod(name);
						screenGetter.setAccessible(true);
						break;
					} catch (ReflectiveOperationException | RuntimeException ignored) {
						screenGetter = null;
					}
				}
			}
		}
		try {
			if (screenField != null) {
				return screenField.get(mc) != null;
			}
			if (screenGetter != null) {
				return screenGetter.invoke(mc) != null;
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// ignorieren
		}
		return false;
	}

	private static boolean isSpear(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_spear");
	}

	private static int findHotbarSlot(LocalPlayer player, Predicate<ItemStack> matcher) {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && matcher.test(stack)) {
				return i;
			}
		}
		return -1;
	}

	private static Vec3 targetCenter(Entity entity) {
		return entity.position().add(0, entity.getBbHeight() * 0.5, 0);
	}

	private static void aimAt(LocalPlayer player, Vec3 point) {
		Vec3 eye = player.getEyePosition();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		player.setYRot(yaw);
		player.setXRot(pitch);
	}

	/**
	 * Speer-Stich wie ein normaler Linksklick. Bevorzugt Minecrafts eigene
	 * startAttack()-Methode (der Stich funktioniert damit auch ohne Treffer),
	 * als Fallback ein direkter Angriff auf das Ziel.
	 */
	private static void jab(Minecraft mc, LocalPlayer player) {
		if (!startAttackLookedUp) {
			startAttackLookedUp = true;
			try {
				startAttackMethod = Minecraft.class.getDeclaredMethod("startAttack");
				startAttackMethod.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException e) {
				startAttackMethod = null;
			}
		}

		if (startAttackMethod != null) {
			try {
				startAttackMethod.invoke(mc);
				return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				startAttackMethod = null;
			}
		}

		if (target != null) {
			mc.gameMode.attack(player, target);
		}
		player.swing(InteractionHand.MAIN_HAND);
	}
}
