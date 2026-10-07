#!/usr/bin/env python3
"""Read-only V1 fluid / V2 file / V3 storage lifecycle checking; no JVM or production solver."""
import argparse
import json
import sys
from pathlib import Path
from _network_audit import CheckError, inspect_path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, help="network-ledger-v1, file-lifecycle-v2, storage-lifecycle-v3 JSON, or manifest-v4 bundle")
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
        v2 = report.get("modelKind") in ("COHERENT_FILE_DATAFLOW_V2", "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2")
        v3 = report.get("modelKind") in ("COHERENT_STORAGE_DATAFLOW_V3", "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3")
        label = "STORAGE_LIFECYCLE_CHECK" if v3 else "FILE_LIFECYCLE_CHECK" if v2 else "NETWORK_LEDGER_CHECK"
        print(label + " " + report["status"] + " completeCaptureCertified=" + str(report["completeCaptureCertified"]).lower())
        print("scope=" + report["scope"])
        if report.get("dataflowAssignmentContextChecked"):
            print("dataflowAssignmentContextChecked=true liveProgressReplayed=false")
            print("dataflowAssignmentScope=" + report["dataflowAssignmentScope"])
        if v2 or v3:
            print("fluidServiceAccountingCertified=false contextualRunChecked=" + str(report["contextualRunChecked"]).lower())
            if report["completeCaptureCertified"]:
                print("copies=" + str(report["admissionCount"]) + " activeCopies=" + str(report["activeCopyCount"])
                      + " waitingJobs=" + str(report["waitingJobCount"]))
                if v3:
                    print("pendingOutputFiles=" + str(report["pendingOutputFileCount"])
                          + " waitingStoreInputs=" + str(report["waitingStoreInputCount"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
