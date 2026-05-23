# CropCenter proguard rules.
#
# Records under non-model packages — ReplaceStrategy.VerifyFailure, ExportPipeline.WriteOutcome,
# SaveController.PriorSaveSnapshot, ImageLoadController.MetadataExtraction, crop/ExportResult,
# graft/EditAligner.Result, GraftController.SourceSnapshot, metadata/JpegSegment,
# metadata/ExtendedXmpReassembler.ExtendedXmpChunk, metadata/GainMapComposer.ComposeResult,
# metadata/XmpItemLengthPatcher.SegmentPatchResult, util/AiRegionDetector.AiMask,
# view/RotationRulerView.TickConfig, view/FolderPickerDialog.SaveChoices. Records are reflected
# through Stream::map / Comparator::comparing in several pipelines (ExtendedXmpReassembler in
# particular); without explicit -keep, R8 in release builds could rename their accessors and
# break Stream-based callers. The -keepclassmembers form preserves component accessors + ctor
# without preventing class-level optimisation.
-keepclassmembers class com.cropcenter.model.** { *; }
-keepclassmembers class com.cropcenter.crop.** { *; }
-keepclassmembers class com.cropcenter.graft.** { *; }
-keepclassmembers class com.cropcenter.metadata.** { *; }
-keepclassmembers class com.cropcenter.util.AiRegionDetector$* { *; }
-keepclassmembers class com.cropcenter.view.RotationRulerView$* { *; }
-keepclassmembers class com.cropcenter.view.FolderPickerDialog$SaveChoices { *; }
-keepclassmembers class com.cropcenter.ReplaceStrategy$* { *; }
-keepclassmembers class com.cropcenter.ExportPipeline$* { *; }
-keepclassmembers class com.cropcenter.SaveController$* { *; }
-keepclassmembers class com.cropcenter.ImageLoadController$* { *; }
-keepclassmembers class com.cropcenter.GraftController$* { *; }
