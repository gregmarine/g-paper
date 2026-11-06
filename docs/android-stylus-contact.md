# Android stylus contact ownership on BOOX

The BOOX engine tracks Android stylus DOWN events when one reaches the native
view, even though the raw SDK normally owns ink capture. Returning the SDK's
`false` result from that DOWN loses Android's subsequent UP/CANCEL delivery.
The shared pen-down flag can then remain set, blocking host palm gates and sync
application until the view is recreated.

On a NoteAir5C running SpareThought's preview, an injected five-event stylus
contact reproduced this condition. A read-only JDWP field inspection showed
`penDown=false` before and `penDown=true` afterwards, with `penHovering=false`,
no active app selection, and an idle lasso recognizer. This explains the injected
input reproduction; it does not diagnose a historical physical-pen ANR.

The adapter now consumes the Android stylus stream after tracking it, regardless
of the SDK's return value, so Android delivers the matching UP/CANCEL. It does not
synthesize software ink or change the raw SDK drawing/erasing callbacks. Finger
input still follows the existing base-class path. NONE/SMUDGE and the lasso
fallback retain their existing base-class handling.

`OnyxPaperViewInputTest` uses Android parent dispatch without a hardware pipeline.
The UP and CANCEL tests fail before the repair and pass afterwards for PEN and
ERASER. The third test checks ordinary finger pass-through. Both core and BOOX
release builds pass. Repeat the injected contact and remote-application check in
the consuming app after adopting the immutable artifacts; real pen/live-ink
acceptance remains distinct from injected input.

## Normalized cancellation and raw ownership

The first repair fixed injected UP on the NoteAir5C, but injected CANCEL still
latched the gate. A debugger event trace found CANCEL with `TOOL_TYPE_UNKNOWN`
arriving at the base engine. Classifying the pointer before recognizing the end
had bypassed Onyx's pen-up handling.

The adapter now records whether Android owns the contact. A normalized CANCEL
ends that contact regardless of pointer type. A raw drawing/erasing begin takes
over ownership; an Android UP/CANCEL then cannot clear the gate while the SDK's
raw contact is active. Explicit input interruption and view release discard the
Android ownership flag. This does not manufacture raw ink or time out real input.

Two additional regressions failed on the first repair: unknown-pointer cancellation
and Android end events during raw contact (both begin orders). All five focused
tests pass after the ownership change. Repeat device cancellation and sync on the
new immutable consumer build before claiming installed acceptance.
