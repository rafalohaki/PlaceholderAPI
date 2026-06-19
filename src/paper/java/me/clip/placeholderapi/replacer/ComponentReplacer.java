package me.clip.placeholderapi.replacer;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class ComponentReplacer {
    @NotNull
    public static Component replace(@NotNull final Component component, @NotNull final Function<String, String> replacer, @Nullable final Function<String, Component> deserializer) {
        return rebuild(component, replacer, deserializer);
    }

    @NotNull
    private static Component rebuild(@NotNull final Component component, @NotNull final Function<String, String> replacer, @Nullable final Function<String, Component> deserializer) {
        final Component rebuilt = switch (component) {
            case TextComponent text -> {
                final String replaced = replacer.apply(text.content());
                yield deserializer == null ? Component.text(replaced) : deserializer.apply(replaced);
            }
            case TranslatableComponent translatable -> {
                final List<Component> arguments = new ArrayList<>();
                for (final ComponentLike arg : translatable.arguments()) {
                    arguments.add(rebuild(arg.asComponent(), replacer, deserializer));
                }
                yield Component.translatable(translatable.key(), arguments);
            }
            case KeybindComponent keybind -> Component.keybind(keybind.keybind());
            case ScoreComponent score -> Component.score(score.name(), score.objective());
            case SelectorComponent selector -> Component.selector(selector.pattern());
            default -> Component.empty();
        };

        final Component styled = rebuilt.style(rebuildStyle(component.style(), replacer, deserializer));

        if (component.children().isEmpty()) {
            return styled;
        }

        final List<Component> children = new ArrayList<>();
        for (final Component child : component.children()) {
            children.add(rebuild(child, replacer, deserializer));
        }
        return styled.children(children);
    }

    @NotNull
    private static Style rebuildStyle(@NotNull final Style style, @NotNull final Function<String, String> replacer, @Nullable final Function<String, Component> deserializer) {
        final Style.Builder builder = style.toBuilder();
        final ClickEvent click = style.clickEvent();

        if (click != null) {
            builder.clickEvent(rebuildClickEvent(click, replacer));
        }

        final HoverEvent<?> hover = style.hoverEvent();

        if (hover != null) {
            builder.hoverEvent(rebuildHoverEvent(hover, replacer, deserializer));
        }

        return builder.build();
    }

    @NotNull
    private static ClickEvent rebuildClickEvent(@NotNull final ClickEvent click, @NotNull final Function<String, String> replacer) {
        if (!(click.payload() instanceof ClickEvent.Payload.Text text)) {
            return click;
        }

        final String replaced = replacer.apply(text.value());

        return switch (click.action()) {
            case OPEN_URL -> ClickEvent.openUrl(replaced);
            case OPEN_FILE -> ClickEvent.openFile(replaced);
            case RUN_COMMAND -> ClickEvent.runCommand(replaced);
            case SUGGEST_COMMAND -> ClickEvent.suggestCommand(replaced);
            case COPY_TO_CLIPBOARD -> ClickEvent.copyToClipboard(replaced);
            default -> click;
        };
    }

    @NotNull
    private static HoverEvent<?> rebuildHoverEvent(@NotNull final HoverEvent<?> hover, @NotNull final Function<String, String> replacer, @Nullable final Function<String, Component> deserializer) {
        return switch (hover.value()) {
            case Component value -> HoverEvent.showText(rebuild(value, replacer, deserializer));
            case HoverEvent.ShowItem item -> rebuildShowItem(item, replacer);
            case HoverEvent.ShowEntity entity -> {
                final Component rebuiltName = entity.name() == null ? null : rebuild(entity.name(), replacer, deserializer);
                yield HoverEvent.showEntity(entity.type(), entity.id(), rebuiltName);
            }
            default -> hover;
        };
    }

    @NotNull
    private static HoverEvent<?> rebuildShowItem(@NotNull final HoverEvent.ShowItem item, @NotNull final Function<String, String> replacer) {
        // Since Minecraft 1.20.5 item hover events use data components instead of raw NBT.
        final Map<Key, DataComponentValue> components = item.dataComponents();

        if (!components.isEmpty()) {
            final Map<Key, DataComponentValue> rebuilt = new HashMap<>();

            for (final Map.Entry<Key, DataComponentValue> entry : components.entrySet()) {
                if (entry.getValue() instanceof BinaryTagHolder holder) {
                    rebuilt.put(entry.getKey(), BinaryTagHolder.binaryTagHolder(replacer.apply(holder.string())));
                } else {
                    rebuilt.put(entry.getKey(), entry.getValue());
                }
            }

            return HoverEvent.showItem(item.item(), item.count(), rebuilt);
        }

        return HoverEvent.showItem(item);
    }
}