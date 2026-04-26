package io.github.ganyuke.peoplehunt.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class HtmlUtil {
    private HtmlUtil() {}

    public static String componentToHtml(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        render(component, Style.empty(), out);
        return out.toString();
    }

    public static String escape(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static void render(Component component, Style parent, StringBuilder out) {
        Style style = parent.merge(component.style());
        ClickEvent click = style.clickEvent();
        String tag = click != null && click.action() == ClickEvent.Action.OPEN_URL ? "a" : "span";
        boolean opened = hasRenderableContent(component);
        if (opened) {
            out.append('<').append(tag);
            if ("a".equals(tag)) {
                out.append(" href=\"").append(escape(clickPayloadText(click))).append("\" target=\"_blank\" rel=\"noopener noreferrer\"");
            }
            String styleAttr = styleToCss(style);
            if (!styleAttr.isBlank()) {
                out.append(" style=\"").append(styleAttr).append("\"");
            }
            String title = hoverTitle(component, click);
            if (!title.isBlank()) {
                out.append(" title=\"").append(escape(title)).append("\"");
            }
            if (click != null && click.action() != ClickEvent.Action.OPEN_URL) {
                out.append(" data-click-action=\"").append(escape(click.action().name())).append("\"");
                out.append(" data-click-value=\"").append(escape(clickPayloadText(click))).append("\"");
            }
            out.append('>');
            String text = resolveText(component);
            if (!text.isEmpty()) {
                appendEscapedText(out, text);
            }
        }
        for (Component child : component.children()) {
            render(child, style, out);
        }
        if (opened) {
            out.append("</").append(tag).append('>');
        }
    }

    private static boolean hasRenderableContent(Component component) {
        return !resolveText(component).isEmpty() || component.children().isEmpty();
    }

    private static void appendEscapedText(StringBuilder out, String text) {
        String escaped = escape(text).replace("\n", "<br>");
        out.append(escaped);
    }

    private static String resolveText(Component component) {
        if (component instanceof TextComponent text) {
            return text.content();
        }
        if (component instanceof TranslatableComponent translatable) {
            return PrettyNames.translate(translatable.key(), translatable.fallback(), plainArguments(translatable));
        }
        String reflected = invokeString(component, "keybind", "pattern", "value", "selector", "nbtPath");
        if (!reflected.isBlank()) {
            return reflected;
        }
        return "";
    }

    /**
     * Adventure has changed how translatable arguments are exposed across versions. Read them
     * reflectively so the renderer can still flatten chat/tooltips without hard-binding to one
     * exact API shape.
     */
    private static List<String> plainArguments(TranslatableComponent component) {
        List<String> args = new ArrayList<>();
        appendArgumentsReflectively(args, component);
        return args;
    }

    private static void appendArgumentsReflectively(List<String> out, Object component) {
        if (appendArgumentsViaAccessor(out, component, "args")) {
            return;
        }
        appendArgumentsViaAccessor(out, component, "arguments");
    }

    private static boolean appendArgumentsViaAccessor(List<String> out, Object component, String methodName) {
        try {
            Method method = component.getClass().getMethod(methodName);
            Object value = method.invoke(component);
            if (!(value instanceof Iterable<?> iterable)) {
                return false;
            }
            for (Object entry : iterable) {
                String plain = coercePlainArgument(entry);
                if (plain != null) {
                    out.add(plain);
                }
            }
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }


    private static String coercePlainArgument(Object entry) {
        if (entry == null) {
            return null;
        }
        if (entry instanceof Component argumentComponent) {
            String plain = Text.plain(argumentComponent);
            return plain == null ? "" : plain;
        }
        for (String methodName : new String[]{"asComponent", "component", "value", "argument"}) {
            try {
                Method method = entry.getClass().getMethod(methodName);
                Object value = method.invoke(entry);
                if (value instanceof Component component) {
                    String plain = Text.plain(component);
                    return plain == null ? "" : plain;
                }
                if (value != null && !(value instanceof Iterable<?>)) {
                    String plain = value.toString();
                    if (!plain.isBlank()) {
                        return plain;
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next common accessor.
            }
        }
        String plain = String.valueOf(entry);
        return plain.isBlank() ? null : plain;
    }

    private static String invokeString(Component component, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = component.getClass().getMethod(methodName);
                Object value = method.invoke(component);
                if (value == null) {
                    continue;
                }
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next method.
            }
        }
        return "";
    }

    /**
     * Click payloads are modeled as typed payload objects in current Adventure. Older code used
     * direct string access, so we unwrap the payload reflectively and fall back to toString().
     */
    private static String clickPayloadText(ClickEvent click) {
        if (click == null) {
            return "";
        }
        try {
            Object payload = click.payload();
            if (payload == null) {
                return "";
            }
            Method valueMethod = payload.getClass().getMethod("value");
            Object value = valueMethod.invoke(payload);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException ignored) {
            return click.payload().toString();
        }
    }

    private static String hoverTitle(Component component, ClickEvent click) {
        StringBuilder builder = new StringBuilder();
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null) {
            Object value = hover.value();
            if (value instanceof Component hoverComponent) {
                builder.append(Text.plain(hoverComponent));
            } else if (value != null) {
                builder.append(String.valueOf(value));
            }
        }
        if (click != null && click.action() != ClickEvent.Action.OPEN_URL) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(click.action().name().toLowerCase(Locale.ROOT)).append(": ").append(clickPayloadText(click));
        }
        return builder.toString();
    }

    private static String styleToCss(Style style) {
        List<String> parts = new ArrayList<>();
        TextColor color = style.color();
        if (color != null) {
            parts.add("color:" + color.asHexString());
        }
        if (style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
            parts.add("font-weight:bold");
        }
        if (style.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE) {
            parts.add("font-style:italic");
        }
        boolean underline = style.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE;
        boolean strike = style.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE;
        if (underline || strike) {
            StringBuilder deco = new StringBuilder("text-decoration:");
            if (underline) {
                deco.append(" underline");
            }
            if (strike) {
                deco.append(" line-through");
            }
            parts.add(deco.toString().trim());
        }
        if (style.decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE) {
            parts.add("filter:blur(1px)");
        }
        return String.join(";", parts);
    }
}
