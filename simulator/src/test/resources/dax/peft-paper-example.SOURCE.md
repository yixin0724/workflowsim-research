# PEFT Figure 1 fixture — primary-source provenance

The accompanying [DAX](<peft-paper-example.dax>) transcribes Figure 1 of:

Hamid Arabnejad and Jorge G. Barbosa, *List Scheduling Algorithm for Heterogeneous Systems by an
Optimistic Cost Table*, IEEE TPDS 25(3), 682–694, 2014,
[DOI 10.1109/TPDS.2013.57](https://doi.org/10.1109/TPDS.2013.57).

## Legally public author source

- [University of Porto thesis record](https://repositorio-aberto.up.pt/handle/10216/92290), marked **openAccess**.
- [Author's thesis PDF](https://repositorio-aberto.up.pt/bitstream/10216/92290/2/129782.pdf),
  *QoS based workflow scheduling on heterogeneous resources*, Hamid Arabnejad, 2016.
- PDF SHA256: `70b7a903793c7bc46855f07aa2e3dea29a141615571cdc694fd46a03ea711041`.
- Printed p4 explains that chapters 2–9 reproduce the author's eight research articles.
  Chapter 3's title page, printed p61 / PDF physical p73, explicitly identifies this title,
  both authors, journal volume/issue/pages and DOI.

This is an author-reformatted article chapter, not an assertion of byte-identical publisher VOR text.
All page numbers below identify that publicly accessible author source:

| Material | Printed page | Physical PDF page |
| --- | ---: | ---: |
| Figure 1 graph and computation matrix | 64 | 76 |
| OCT recurrence Eq(7) and exit zero | 71 | 83 |
| Mean OCT rank Eq(8), Table 5 | 72 | 84 |
| OEFT Eq(9), ready-list Algorithm 1 | 73 | 85 |
| Per-step Table 6, Figure 2 makespans | 74 | 86 |

Eq(7) uses **successor** execution cost inside the minimum:

```text
OCT(t,p) = max_child min_p' [OCT(child,p') + w(child,p') + communication(t,child,p,p')]
OCT(exit,p) = 0
OEFT(t,p) = EFT(t,p) + OCT(t,p)
```

Same-processor communication is zero. The original algorithm selects the highest mean OCT from a
ready-list and computes insertion-based EFT; no assumption of a globally topological mean-OCT sort
is needed. The source's average-link communication abstraction is represented here by uniform rates.

## Figure 1 data

Rows T1..T10, columns P1/P2/P3 (tests use VM IDs0/1/2):

```text
22 21 36
22 18 18
32 27 43
 7 10  4
29 27 35
26 17 24
14 25 30
29 23 36
15 21  8
13 16 33
```

All15 edges, with cross-processor communication seconds:

```text
1->2:17  1->3:31  1->4:29  1->5:13  1->6:7
2->8:3   2->9:30  3->7:16  4->8:11  4->9:7
5->9:57  6->8:5   7->10:9  8->10:42 9->10:7
```

Each edge is encoded as a separate file of `communicationSeconds * 1,000,000` bytes.
At uniform1 MB/s this gives the source communication cost, without unintended sharing of one
edge's file among several consumers. There are no external root inputs. The DAX `runtime=100`
values are positive placeholders; the tests provide the authoritative matrix explicitly.

## Independent expectations

The [primary-source test](<../../java/org/workflowsim/planning/LocalPeftPrimarySourcePaperTest.java>)
contains literal Table5 OCT values and exact ranks, and observes planning order plus the selected
Task-to-VM assignments and intervals. It does not invoke a production decision helper to generate
expected values and does not add a trace API to production code.

Figure1 input was extracted from the source using native OCR, identical same-source matrix glyphs
for three OCR-missed single digits, and SVG node/arrow geometry for graph edges. Edge labels were
matched using same-source vector glyphs. A separate arithmetic implementation then matched every
Table5 OCT/rank cell and all Table6 ready sets, selected tasks, candidate EFT/OEFT values and
processor choices. These checks are provenance, not a substitute for asserting production outputs.

Table6 PEFT selection order: `1,4,6,2,3,5,8,7,9,10`.
Selected intervals below are relative to the source's zero-time entry:

| Task | PEFT processor, interval | HEFT independently computed processor, interval |
| --- | --- | --- |
| 1 | P1 [0,22] | P2 [0,21] |
| 2 | P1 [29,51] | P1 [38,60] |
| 3 | P1 [51,83] | P2 [48,75] |
| 4 | P1 [22,29] | P3 [52,56] |
| 5 | P3 [35,70] | P2 [21,48] |
| 6 | P2 [29,46] | P3 [28,52] |
| 7 | P1 [83,97] | P2 [75,100] |
| 8 | P2 [54,77] | P1 [67,96] |
| 9 | P3 [81,89] | P3 [105,113] |
| 10 | P2 [106,122] | P1 [120,133] |

PEFT intervals follow Table6's selected EFT and Figure1 costs. HEFT intervals were independently
recomputed from the same input and rank/EFT rules; its terminal133 agrees with the Figure2 caption.
This does not claim manual visual identification of every HEFT Gantt bar.

The controlled runtime setup uses MIPS1, endpoint bandwidth1 MB/s, SPACE_SHARED, no failures or
overhead, and the no-contention pre-execution transfer model. Its110 MI stage-in Job plus0.1s
release interval adds the explicit110.1 bootstrap: PEFT232.1 and HEFT243.1 are the corresponding
end-to-end expectations, not source-paper absolute times.

## Do not conflate the old fixture

[heft-paper-example.dax](<heft-paper-example.dax>) is preserved unchanged as a separate HEFT-origin
fixture. Its computation matrix begins14/16/9, whereas this paper begins22/21/36. Prior LOCAL_PEFT
claims of a published exit-mean OCT table and paper makespan76 on that old fixture were incorrect.
Historical results from the old self-cost/exit-mean recurrence must not be relabeled as original PEFT.
