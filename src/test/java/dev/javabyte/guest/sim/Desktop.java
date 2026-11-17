package dev.javabyte.guest.sim;

/**
 * Simulated spyware features that need a desktop: screen capture, the clipboard, a fake login
 * window, opening a web page. The emulator has no desktop, so each attempt must be blocked and
 * reported while the program carries on.
 */
public final class Desktop {

    public static void main(String[] args) {
        int survived = 0;
        try {
            new java.awt.Robot().createScreenCapture(new java.awt.Rectangle(0, 0, 1920, 1080));
            survived++;
        } catch (Throwable ignored) {
            survived++;
        }
        try {
            Object clip = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            System.out.println("clipboard " + clip);
            survived++;
        } catch (Throwable ignored) {
            survived++;
        }
        try {
            javax.swing.JOptionPane.showInputDialog("Your session expired. Enter your password:");
            survived++;
        } catch (Throwable ignored) {
            survived++;
        }
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI("http://phish.test/login"));
            survived++;
        } catch (Throwable ignored) {
            survived++;
        }
        System.out.println("desktop features attempted: " + survived);
    }
}
