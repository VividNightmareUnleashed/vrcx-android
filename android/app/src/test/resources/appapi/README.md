# AppApi test vectors

`vectors.json` and `screenshots/*.png` were produced by a throwaway .NET 10 console program (not kept in the
repository) that compiled verbatim copies of upstream `Dotnet/ScreenshotMetadata/*.cs`, the relevant methods of
`Dotnet/AppApi/Common/{AppApiCommon,ImageSaving,Screenshot,Utils}.cs`, and referenced upstream `Dotnet/libs/librsync.net.dll`
and `Blake2Sharp.dll`, `Newtonsoft.Json 13.0.4` and `SixLabors.ImageSharp 3.1.12` (the versions in
`Dotnet/VRCX-Electron.csproj`). It ran on Windows with culture en-US and the time zone recorded in `timeZone`.

- `colour`, `colourBulk`: `GetColourFromUserID`, `GetColourBulk`.
- `files`: `MD5File`, `FileLength`, `SignFile` for inputs given inline (`b64`) or by generator
  (`pattern`: byte i = (i * 7) & 0xFF; `lcg`: seed = seed * 1664525 + 1013904223 (uint32), byte = seed >> 24).
- `makeValidFileName`: `MakeValidFileName` with the Windows invalid-character sets.
- `screenshots`: per fixture, `AppApi.GetScreenshotMetadata`, `GetExtraScreenshotData(path, true)` without
  `creationDate`, `ScreenshotHelper.ReadTextMetadata`, and the SHA-256 of the file after `DeleteScreenshotMetadata`.
  Paths are written as `{dir}/<name>`; line breaks are normalised to `\n`.
- `search`: `ScreenshotHelper.FindScreenshots` over the fixture folder.
- `copyITxt`, `writeVrcx`: SHA-256 of a PNG after `PNGFile.WriteChunk` of the iTXt chunks (the `CropPrintImage` copy
  step) and after `WriteVRCXMetadata`.
- `resize`, `resizePrint`: output sizes of `ResizeImageToFitLimits` and the red picture's bounding box after
  `ResizePrintImage` for solid images.
- `floatFormat`, `fileSize`, `xmpDates`, `jsonDates`: Newtonsoft float text, the `"0.00"` MB text, `DateTime.TryParse`
  and Newtonsoft `RoundtripKind` date handling.
