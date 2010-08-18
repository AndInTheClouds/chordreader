package com.nolanlawson.chordfinder.chords;

import com.nolanlawson.chordfinder.util.ArrayUtil;

/**
 * Class representing the main, root note of a chord.  Note that this can be overriden, e.g. with a chord
 * like "C/F."   But it still counts as the main, defining note of the chord
 * @author nolan
 *
 */
public enum ChordRoot {
	
	A (new String[]{"A"}),
	Bb (new String[]{"Bb", "A#", "Asharp", "Bflat"}),
	B (new String[]{"B"}),
	C (new String[]{"C"}),
	Db (new String[]{"Db", "C#", "Dflat", "Csharp"}),
	D (new String[]{"D"}),
	Eb (new String[]{"Eb", "D#", "Eflat", "Dsharp"}),
	E (new String[]{"E"}),
	F (new String[]{"F"}),
	Gb (new String[]{"Gb", "F#", "Gflat", "Gsharp"}),
	G (new String[]{"G"}),
	Ab (new String[]{"Ab", "G#", "Aflat", "Gsharp"});
	
	private String[] aliases;
	
	ChordRoot(String[] aliases) {
		this.aliases = aliases;
	}

	public String[] getAliases() {
		return aliases;
	}
	
	public static String[] getAllAliases() {
		String[] result = new String[0];
		
		for (ChordRoot chordRoot : values()) {
			result = ArrayUtil.concatenate(result, chordRoot.aliases);
		}
		
		return result;
	}
}