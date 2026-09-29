#!/usr/bin/env python3
"""Read-only independent network-ledger checking. Uses no JVM or production allocation solver."""
import argparse
import json
import sys
from pathlib import Path
from _network_audit import CheckError, inspect_path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="network-ledger-v1 JSON or manifest-v4 bundle")
    parser.add_argument("--json", action="store_true", help="emit structured status, including incomplete-prefix coverage")
    args = parser.parse_args(argv)
    try:
        report = inspect_path(args.path)
    except CheckError as error:
        if args.json:
            print(json.dumps({"status": "EVIDENCE_INVALID", "completeCaptureCertified": False, "error": str(error)}, allow_nan=False))
        else:
            print("NETWORK_LEDGER_CHECK EVIDENCE_INVALID: " + str(error), file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(report, indent=2, allow_nan=False))
    else:
        print("NETWORK_LEDGER_CHECK " + report["status"] + " completeCaptureCertified=" + str(report["completeCaptureCertified"]).lower())
        print("scope=" + report["scope"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
