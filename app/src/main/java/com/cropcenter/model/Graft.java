package com.cropcenter.model;

import com.cropcenter.util.AiRegionDetector.AiMask;

/**
 * Output of a successful Apply External Edit graft. Bundles the three values that flow
 * from GraftController.onEditPicked's bg thread to MainActivity's apply step:
 *
 *   - bytes:        the spliced JPEG (original's identity metadata + edit's pixel content
 *                   + original's HDR package + SEFT trailer if any).
 *   - displayName:  suggested filename for the next Save (Save dialog seeds this).
 *   - aiMask:       AI-modified pixel region (or null when no fill was detected). Drives
 *                   GainMapInpainter at HDR re-encode time so the gain map's boost values
 *                   inside the AI fill match Generative Remove's "this region looks like
 *                   its neighbors" intent. Stashed on CropState by installGraft.
 *
 * Records keep the GraftReadyHandler.onReady signature stable as we add or remove fields,
 * and let CropState.installGraft accept a single argument that carries every post-apply
 * side-effect together — avoiding the historical "set graftApplied AND aiMask, both AFTER
 * applyImageBytes which calls state.reset()" ordering trap.
 */
public record Graft(byte[] bytes, String displayName, AiMask aiMask)
{
	/**
	 * True when the graft carries a non-empty AI-region mask that GainMapInpainter should
	 * act on. False for grafts with no detected AI fill (the splice still needs a canvas-
	 * encode pass for spatial alignment, but the inpaint step is skipped).
	 */
	public boolean hasAiMask()
	{
		return aiMask != null && aiMask.hasMaskedPixels();
	}
}
