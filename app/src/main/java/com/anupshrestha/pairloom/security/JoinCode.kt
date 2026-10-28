package com.anupshrestha.pairloom.security

import java.security.SecureRandom

/**
 * A join code (Phase 7, G6): three words such as "maple lotus otter", read out once when a group
 * starts. Anyone with the code joins the group automatically when in range; nobody else can.
 *
 * Three words from [WORDS] (256 each) give about 16.7 million codes. That is far too few to use
 * the code as a key directly, so nothing public is ever derived from it: it is only tested inside
 * the J-PAKE handshake ([CodeJoin]), one guess per connection, and the host slows down repeated
 * failures ([CodeGuard]).
 */
class JoinCode private constructor(val words: List<String>) {
    /** The canonical form, the same on every phone: "maple-river-stone". */
    val text: String get() = words.joinToString("-")

    /** How it is shown and read out. */
    val spoken: String get() = words.joinToString(" ")

    override fun equals(other: Any?): Boolean = other is JoinCode && other.words == words
    override fun hashCode(): Int = words.hashCode()

    /** The code is a secret: it never goes into a log by accident. */
    override fun toString(): String = "JoinCode(<hidden>)"

    companion object {
        const val WORD_COUNT = 3

        fun generate(random: SecureRandom = SecureRandom()): JoinCode = JoinCode(List(WORD_COUNT) { WORDS[random.nextInt(WORDS.size)] })

        /**
         * Reads what someone typed: any case, any separators ("Maple river-STONE", "maple,river
         * stone"). Returns null unless it is exactly three words from the list.
         */
        fun parse(input: String): JoinCode? {
            val parts = input.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            if (parts.size != WORD_COUNT || parts.any { it !in WORD_SET }) return null
            return JoinCode(parts)
        }

        /** The words a code is made of: short, common and easy to say, each one different. */
        val WORDS: List<String> = listOf(
        "acorn", "amber", "anchor", "apple", "apron", "arrow", "aspen", "atlas", "autumn", "badge",
        "bagel", "baker", "bamboo", "banjo", "barley", "basil", "basket", "beach", "beacon",
        "beaver", "berry", "birch", "biscuit", "bison", "blanket", "bloom", "bluff", "bonfire",
        "bottle", "boulder", "bramble", "brass", "bread", "breeze", "brick", "bridge", "brook",
        "bubble", "bucket", "buckle", "buffalo", "bugle", "bunny", "butter", "button", "cabin",
        "cactus", "camel", "camera", "candle", "canoe", "canyon", "carbon", "cargo", "carrot",
        "castle", "cedar", "celery", "cello", "chalk", "cherry", "chess", "chestnut", "chimney",
        "cider", "cinnamon", "circus", "citrus", "clover", "cobalt", "cocoa", "coconut", "comet",
        "compass", "copper", "coral", "cotton", "cougar", "coyote", "crane", "crayon", "creek",
        "cricket", "crystal", "cupcake", "curtain", "cymbal", "daisy", "dolphin", "donkey",
        "dragon", "drum", "dune", "eagle", "easel", "echo", "eclipse", "elbow", "ember", "engine",
        "falcon", "feather", "fern", "fiddle", "finch", "flame", "flannel", "flute", "forest",
        "fossil", "fountain", "fox", "frost", "galaxy", "garden", "garlic", "gecko", "geyser",
        "ginger", "glacier", "globe", "goose", "granite", "grape", "gravel", "guitar", "hammock",
        "harbor", "harp", "hazel", "hedge", "helmet", "heron", "hickory", "hollow", "honey",
        "hornet", "husky", "iceberg", "igloo", "indigo", "island", "ivory", "jacket", "jaguar",
        "jasmine", "jelly", "jigsaw", "juniper", "kayak", "kettle", "kitten", "koala", "ladder",
        "lagoon", "lantern", "lava", "lemon", "lily", "lizard", "llama", "lobster", "locket",
        "lotus", "magnet", "mango", "maple", "marble", "meadow", "melon", "meteor", "mint",
        "mitten", "monsoon", "moose", "mosaic", "muffin", "mustard", "nectar", "needle", "nutmeg",
        "oasis", "oatmeal", "ocean", "olive", "onion", "orbit", "orchid", "otter", "owl", "paddle",
        "panda", "papaya", "parrot", "peach", "peanut", "pebble", "pelican", "pepper", "pickle",
        "pigeon", "pine", "planet", "plum", "pocket", "pony", "poppy", "puffin", "pumpkin",
        "quartz", "quill", "rabbit", "raccoon", "radar", "radish", "rainbow", "raven", "reef",
        "ribbon", "river", "robin", "rocket", "saddle", "salmon", "sapphire", "satchel", "scarf",
        "seashell", "sequoia", "shadow", "sierra", "silver", "sketch", "sled", "sparrow",
        "spinach", "sponge", "spruce", "squash", "starfish", "stone", "summit", "sunset", "swan",
        "tadpole", "tango", "teapot", "thistle", "thunder", "tiger", "timber", "toast", "tomato",
        "topaz", "tortoise", "trumpet",
        )

        private val WORD_SET = WORDS.toHashSet()

        init {
            check(WORDS.size == 256 && WORD_SET.size == 256) { "the join-code word list must hold 256 different words" }
        }
    }
}
