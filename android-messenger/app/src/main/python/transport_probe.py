"""Small import smoke test for the embedded Reticulum/LXMF runtime.

This module intentionally does not initialize a network, create an identity, announce,
or transmit anything. It is safe to call from a diagnostic screen in a later milestone.
"""

def check_runtime():
    import RNS
    import LXMF
    import cryptography

    return {
        "rns_imported": True,
        "lxmf_imported": True,
        "cryptography_version": getattr(cryptography, "__version__", "unknown"),
        "rns_version": getattr(RNS, "__version__", "unknown"),
    }
