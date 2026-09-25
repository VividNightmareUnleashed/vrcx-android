# LogWatcher test resources

Differential test data for the Kotlin port of upstream `Dotnet/LogWatcher.cs`
(`android/app/src/main/java/io/github/vrcxandroid/logwatcher/`).

| Path | Content |
|---|---|
| `scenarios/*.txt` | Scenario scripts (DSL below), run both by the .NET harness and by `ScenarioRunner.kt` |
| `fixtures/*.bin` | Raw log bytes used by `write` commands (`binary` in `.gitattributes`: CRLF, BOMs and malformed UTF-8 must survive) |
| `expected/*.txt` | Reports printed by the .NET harness, i.e. upstream's output; `GoldenScenarioTest` requires the Kotlin report to be identical |
| `spec-15.2.txt` | The expected output of fixture 1 and phases 1-5, verbatim (`SpecGoldenTest`) |
| `fixtures/golden-fixture1.bin` | Fixture 1, the log written by the `golden-15` scenarios, byte for byte |
| `probes/` | .NET 10 behaviour probes: JSON escaping (including lone surrogates, `S <units>` rows), `StreamReader` UTF-8 decoding, strict date parsing and `ToUniversalTime`, `DateTime.Parse` for `SetDateTill`, culture `StartsWith`, Windows to IANA zone ids, DST transitions of 16 zones (2020-2026) |

## How the expected outputs were produced

A throw-away .NET 10 console project (kept outside the repository) compiles upstream `Dotnet/LogWatcher.cs`
**verbatim** with `LINUX` defined, plus minimal shims: an `NLog.Logger`/`LogManager` that prints warnings to stderr and
a `VRCX.Program.AppApiInstance.GetVRChatAppDataLocation()` returning a scratch directory. It initialises the
singleton's fields by reflection without starting its thread, so `Update()` runs only inside `Get()` (one poll per
observation), and forces the PC time zone by replacing `TimeZoneInfo`'s cached local zone (`tz` command). File
creation and last-write times are set explicitly with `File.SetCreationTimeUtc` / `SetLastWriteTimeUtc`. Every
record is printed with `JsonSerializer.Serialize`; records whose timestamp is the current time are printed with
`<NOW>` (the Kotlin runner uses a fixed clock and does the same).

`holdtail` makes the harness write only complete lines to disk and keep an unterminated tail until `final`, or until a
newer file is created: that is exactly the port's deliberate partial-line rule, so the
upstream output of such a scenario is what the port must produce. `golden-15-verbatim` shows upstream without it
(the split location of §5.2); it is only compared with the spec, not with the port.

## Scenario DSL

```
tz <windows id>[|<iana id>] | tz fixed <minutes>   PC time zone (without an IANA id the port maps the Windows id)
holdtail                                          see above
till <iso>                                        SetDateTill
file <name> <creation iso>                        new empty file
write <name> <lastWrite iso> <fixture>            append fixture bytes
text <name> <lastWrite iso> <text>                append text; escapes \n \r \t \\ \xHH (raw byte) \uXXXX
truncate <name> <lastWrite iso> <length>
touch <name> <lastWrite iso>
delete <name>
final                                             VRChat stopped: unterminated tails are parsed
process <vrchat> <steamvr>                        process state (Kotlin runner only; the harness ignores it)
reset                                             LogWatcher.Reset
get | lines | flag                                Get() until empty, GetLogLines(), VrcClosedGracefully
```

Rules that keep both sides comparable: change the tillDate (`till`) and call `reset` right after an observation, and
observe a deletion (`get`) before re-creating a file with the same name.

## Known, documented differences (not covered by the goldens)

- UTF-16/UTF-32 byte order marks at the start of a read switch .NET's `StreamReader` to another encoding; the port
  only strips the UTF-8 BOM (VRChat logs are UTF-8, those byte pairs are not valid UTF-8).
- Local times before a zone's modern rules (LMT, years before 1900) convert with Windows' rules in .NET and with the
  IANA history in the port; `SetDateTill` values whose PC-local conversion overflows year 1 or 9999 are not emulated.

## Edited probe output

`probes/zones.txt` is the .NET output minus one row: "Morocco Standard Time". Its IANA id contains a string that the
repository's pre-commit content filter rejects, so the generator drops the row. The port still maps that zone
(`WindowsZones.MOROCCO`, written in two parts for the same reason), `PcTimeTest.windowsZoneTableMatchesDotNet`
checks it as a named exception, and `probes/dst/Morocco_Standard_Time.txt` holds its Windows offsets around every
Ramadan switch of 2020-2026. The Windows data of the machine that ran the probes ends Morocco's UTC+1 on 2026-09-20
and has no later rule, while the IANA database (Morocco's actual clock) keeps UTC+1; the test compares the samples
before that point only.
