import WebKit

/// The text box the owner last used on the dashboard page: a reply, an answer, the feed composer.
/// The composer bar sends into it while it is there, and hides while the owner types in the page itself.
@MainActor
final class PageTextBox {
    /// What the page reports each time focus moves.
    struct State: Equatable {
        /// A text field on the page has the keyboard.
        var isTyping = false
        /// The box last used is still on the page, so words can go into it.
        var hasBox = false
        /// Its placeholder or label, which says what kind of box it is ("Reply to Builder…").
        var placeholder: String?
    }

    /// Kept apart from the page's own scripts, which share only the page itself with it.
    static let world = WKContentWorld.world(name: "voice-notes")
    static let messageName = "textBox"

    // Remembered on focus, because by the time the mic is let go the page has usually lost focus to it.
    static let script = WKUserScript(source: """
        (() => {
          let box = null, caret = null;
          const typeable = (el) => (el instanceof HTMLTextAreaElement
              || (el instanceof HTMLInputElement && (el.type === 'text' || el.type === 'search'))
              || (el instanceof HTMLElement && el.isContentEditable))
            && !el.readOnly && !el.disabled;
          // Anything that brings up the keyboard, which is wider than what words can go into.
          const keyboard = (el) => el instanceof HTMLTextAreaElement
            || (el instanceof HTMLElement && el.isContentEditable)
            || el instanceof HTMLSelectElement
            || (el instanceof HTMLInputElement
                && !['checkbox', 'radio', 'button', 'submit', 'reset', 'range', 'color', 'file', 'hidden', 'image'].includes(el.type));
          const present = () => !!box && box.isConnected && box.getClientRects().length > 0;
          const label = (el) => (el.getAttribute('aria-label') || el.getAttribute('placeholder')
            || el.dataset?.placeholder || '').trim().slice(0, 200) || null;
          const state = () => ({
            typing: keyboard(document.activeElement),
            hasBox: present(),
            placeholder: present() ? label(box) : null,
          });
          const report = () => window.webkit?.messageHandlers?.textBox?.postMessage(state());

          document.addEventListener('focusin', (event) => {
            if (typeable(event.target)) {
              box = event.target;
              caret = null;
            }
            report();
          }, true);
          // Focus moving between two fields passes through nothing; wait for it to land before saying so.
          document.addEventListener('focusout', () => setTimeout(report, 0), true);
          // Buttons on a phone don't take focus, so pressing the page's own Send would otherwise keep the box.
          document.addEventListener('click', (event) => {
            if (box && event.target instanceof Element && !box.contains(event.target) && event.target.closest('button')) {
              box = null;
              setTimeout(report, 0);
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
            state,
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
          report();
        })();
        """, injectionTime: .atDocumentEnd, forMainFrameOnly: true, in: world)

    weak var webView: WKWebView?
    var onChange: ((State) -> Void)?
    private(set) var state = State()

    /// Whether words would go into the page's text box right now.
    func isThere() async -> Bool {
        await call("return textBox.present()")
    }

    /// Writes the words in at the box's cursor. False when the box has gone.
    func insert(_ text: String) async -> Bool {
        await call("return textBox.insert(text)", arguments: ["text": text])
    }

    /// Asks the page again, for changes no focus event announces: the box's card closing, the page moving on.
    func recheck() async {
        guard let webView else { return }
        let result = try? await webView.callAsyncJavaScript("return textBox.state()", arguments: [:], in: nil, contentWorld: Self.world)
        received(result)
    }

    /// The page's report, from the script's message or from `recheck`.
    func received(_ body: Any?) {
        guard let body = body as? [String: Any] else { return }
        let next = State(
            isTyping: body["typing"] as? Bool ?? false,
            hasBox: body["hasBox"] as? Bool ?? false,
            placeholder: body["placeholder"] as? String
        )
        guard next != state else { return }
        state = next
        onChange?(next)
    }

    private func call(_ body: String, arguments: [String: Any] = [:]) async -> Bool {
        guard let webView else { return false }
        let result = try? await webView.callAsyncJavaScript(body, arguments: arguments, in: nil, contentWorld: Self.world)
        return result as? Bool ?? false
    }
}

/// Passes the page's focus reports to its `PageTextBox`. The web view holds message handlers strongly, so this holds the box weakly.
final class PageTextBoxMessages: NSObject, WKScriptMessageHandler {
    private weak var textBox: PageTextBox?

    @MainActor
    init(_ textBox: PageTextBox) { self.textBox = textBox }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        let body = message.body
        Task { @MainActor [weak textBox] in textBox?.received(body) }
    }
}
