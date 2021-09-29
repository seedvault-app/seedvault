# Seedvault Storage

This is a library for Seedvault storage backup.
It can also be used by other apps wanting to provide storage backup feature.

Please see the [design document](doc/design.md) for more information.

There is also a [demo app](demo) that illustrates the working of the library
and does not need to be a system app with elevated permissions.
It can be built and installed as a regular app requesting permissions at runtime.

## Limitations

The design document mentions several limitations of this initial implementation.
One of them is that you cannot backup more than one device to the same storage location.
