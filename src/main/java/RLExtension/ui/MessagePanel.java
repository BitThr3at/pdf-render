package RLExtension.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;

/** Centred status panel: what happened, and why, when there is nothing to render. */
public final class MessagePanel {

    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel title = new JLabel("", SwingConstants.CENTER);
    private final JLabel detail = new JLabel("", SwingConstants.CENTER);

    public MessagePanel() {
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1f));
        detail.setForeground(Ui.subtleForeground());
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        detail.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setOpaque(false);
        stack.add(Box.createVerticalGlue());
        stack.add(title);
        stack.add(Box.createVerticalStrut(6));
        stack.add(detail);
        stack.add(Box.createVerticalGlue());

        root.add(stack, BorderLayout.CENTER);
        root.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
    }

    public Component component() {
        return root;
    }

    public void show(String titleText, String detailText) {
        title.setText(titleText);
        detail.setText(detailText == null ? "" : detailText);
    }
}
