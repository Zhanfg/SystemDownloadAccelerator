# hotfix3 regression recovery

0.8.0-alpha01-hotfix3 restores the pure platform-framework module runtime used
before the AndroidX live-update experiment.

Reason: this APK is an LSPosed module injected into DownloadProvider/SystemUI.
A normal app dependency can be valid in the module's own process but still make
an injected generation unsafe. The previous hot-reload path removed the old
generation before validating the incoming one, so a new-generation load failure
could leave the target process with no SDA hooks at all.

hotfix3:
- removes AndroidX Core from the injected module APK;
- returns android.useAndroidX to false;
- restores the framework LiveUpdatePublisher from hotfix1;
- preflights critical module classes before old HookHandles are removed;
- aborts a bad hot reload while preserving the existing working generation.

Future ColorOS/Live Activity experiments must be isolated from the injected
DownloadProvider hook runtime rather than adding app-framework dependencies to
the shared module class path.
