import WebKit

/// The text box the owner last used on the dashboard page: a reply, an answer, the feed composer.
/// A voice note goes into it for the owner to check and send, instead of being posted on its own.
@MainActor
final class PageTextBox {
    /// Kept apart from the page's own scripts, which share only the page itself with it.
    private static let world = WKContentWorld.world(name: "voice-notes")

    // Remembered on focus, because by the time the mic is let go the page has usually lost focus to it.
    static let script = WKUserScript(source: """
        (() => {
          let box = null, caret = null;
          const typeable = (el) => (el instanceof HTMLTextAreaElement
              || (el instanceof HTMLInputElement && (el.type === 'text' || el.type === 'search'))
              || (el instanceof HTMLElement && el.isContentEditable))
            && !el.readOnly && !el.disabled;
          const present = () => !!box && box.isConnected && box.getClientRects().length > 0;

          document.addEventListener('focusin', (event) => {
            box = typeable(event.target) ? event.target : null;
            caret = null;
          }, true);
          // Buttons on a phone don't take focus, so pressing the page's own Send would otherwise keep the box.
          document.addEventListener('click', (event) => {
            if (box && event.target instanceof Element && !box.contains(event.target) && event.target.closest('button')) {
              box = null;
            }
          }, true);
          document.addEventListener('selectionchange', () => {
            const selection = document.getSelection();
            if (box?.isContentEditable && selection.rangeCount && box.contains(selection.anchorNode)) {
              caret = selection.getRangeAt(0).cloneRange();
            }
          });

          window.textBox = {
            present,
            insert(text) {
              if (!present()) return false;
              if (box.isContentEditable) {
                box.focus();
                const selection = document.getSelection();
                selection.removeAllRanges();
                if (caret) selection.addRange(caret);
                else { selection.selectAllChildren(box); selection.collapseToEnd(); }
                document.execCommand('insertText', false, text);
                return true;
              }
              const start = box.selectionStart ?? box.value.length;
              const before = box.value.slice(0, start);
              const words = (before && !/\\s$/.test(before) ? ' ' : '') + text;
              box.setRangeText(words, start, box.selectionEnd ?? start, 'end');
              // The page's bindings only see a change that arrives as an input event.
              box.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: words }));
              box.focus();
              return true;
            },
          };
        })();
        """, injectionTime: .atDocumentEnd, forMainFrameOnly: true, in: world)

    weak var webView: WKWebView?

    /// Whether a voice note would go into the page's text box right now.
    func isThere() async -> Bool {
        await call("return textBox.present()")
    }

    /// Writes the words in at the box's cursor. False when the box has gone.
    func insert(_ text: String) async -> Bool {
        await call("return textBox.insert(text)", arguments: ["text": text])
    }

    private func call(_ body: String, arguments: [String: Any] = [:]) async -> Bool {
        guard let webView else { return false }
        let result = try? await webView.callAsyncJavaScript(body, arguments: arguments, in: nil, contentWorld: Self.world)
        return result as? Bool ?? false
    }
}
