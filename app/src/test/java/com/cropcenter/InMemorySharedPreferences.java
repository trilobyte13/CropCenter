package com.cropcenter;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory SharedPreferences fake for the journal seam tests — SaveController.journalInflightTemp /
 * unjournalInflightTemp expose SharedPreferences-taking overloads precisely so the headless suite can drive them
 * without a Context, and this fixture is the suite's implementation of that interface (a hand-rolled fake, per the
 * suite's no-mock convention). Supports the string / string-set surface the save-flow prefs actually use; the
 * numeric getters and editor ops the save flow never touches throw so an accidental dependency fails loudly.
 * setFailCommits simulates a failing prefs store (disk-full commit): pending edits are dropped, commit() reports
 * false, and apply() loses the write silently — exactly the failure shapes the journal contracts document.
 */
final class InMemorySharedPreferences implements SharedPreferences
{
	// Pending-edit accumulator handed out by edit(); commit() / apply() fold the pending ops into the outer
	// store unless failCommits is set.
	private final class InMemoryEditor implements Editor
	{
		private final Map<String, Long> pendingLongs = new HashMap<>();
		private final Map<String, Set<String>> pendingStringSets = new HashMap<>();
		private final Map<String, String> pendingStrings = new HashMap<>();

		@Override
		public void apply()
		{
			// apply() has no failure channel — a failing store just loses the write silently, which is
			// the lost-removal tolerance the in-flight temp journal documents.
			if (!failCommits)
			{
				applyPending();
			}
		}

		@Override
		public Editor clear()
		{
			throw new UnsupportedOperationException("not part of the save-flow prefs surface");
		}

		@Override
		public boolean commit()
		{
			if (failCommits)
			{
				return false;
			}
			applyPending();
			return true;
		}

		@Override
		public Editor putBoolean(String key, boolean value)
		{
			throw new UnsupportedOperationException("not part of the save-flow prefs surface");
		}

		@Override
		public Editor putFloat(String key, float value)
		{
			throw new UnsupportedOperationException("not part of the save-flow prefs surface");
		}

		@Override
		public Editor putInt(String key, int value)
		{
			throw new UnsupportedOperationException("not part of the save-flow prefs surface");
		}

		@Override
		public Editor putLong(String key, long value)
		{
			pendingLongs.put(key, value);
			return this;
		}

		@Override
		public Editor putString(String key, String value)
		{
			pendingStrings.put(key, value);
			return this;
		}

		@Override
		public Editor putStringSet(String key, Set<String> values)
		{
			pendingStringSets.put(key, new HashSet<>(values));
			return this;
		}

		@Override
		public Editor remove(String key)
		{
			throw new UnsupportedOperationException("not part of the save-flow prefs surface");
		}

		private void applyPending()
		{
			longs.putAll(pendingLongs);
			strings.putAll(pendingStrings);
			stringSets.putAll(pendingStringSets);
		}
	}

	private final Map<String, Long> longs = new HashMap<>();
	private final Map<String, Set<String>> stringSets = new HashMap<>();
	private final Map<String, String> strings = new HashMap<>();

	private boolean failCommits;

	@Override
	public boolean contains(String key)
	{
		throw new UnsupportedOperationException("not part of the save-flow prefs surface");
	}

	@Override
	public Editor edit()
	{
		return new InMemoryEditor();
	}

	@Override
	public Map<String, ?> getAll()
	{
		throw new UnsupportedOperationException("not part of the save-flow prefs surface");
	}

	@Override
	public boolean getBoolean(String key, boolean defValue)
	{
		throw new UnsupportedOperationException("not part of the save-flow prefs surface");
	}

	@Override
	public float getFloat(String key, float defValue)
	{
		throw new UnsupportedOperationException("not part of the save-flow prefs surface");
	}

	@Override
	public int getInt(String key, int defValue)
	{
		throw new UnsupportedOperationException("not part of the save-flow prefs surface");
	}

	@Override
	public long getLong(String key, long defValue)
	{
		Long value = longs.get(key);
		return value != null ? value : defValue;
	}

	@Override
	public String getString(String key, String defValue)
	{
		return strings.getOrDefault(key, defValue);
	}

	@Override
	public Set<String> getStringSet(String key, Set<String> defValues)
	{
		Set<String> values = stringSets.get(key);
		return values != null ? new HashSet<>(values) : defValues;
	}

	@Override
	public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener)
	{
		throw new UnsupportedOperationException("not part of the save-flow prefs surface");
	}

	@Override
	public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener)
	{
		throw new UnsupportedOperationException("not part of the save-flow prefs surface");
	}

	/**
	 * Make every subsequent commit() report false and every apply() lose its write, without disturbing already
	 * stored values — the fake's stand-in for a failing prefs store.
	 *
	 * @param fail true to start failing writes; false restores normal behaviour
	 */
	void setFailCommits(boolean fail)
	{
		failCommits = fail;
	}
}
