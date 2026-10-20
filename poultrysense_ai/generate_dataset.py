import csv
import json
import os

data_pairs = [
    # 1. Normal deep rest / sleeping
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #12\n"
            "Stationary Duration: 180.0s (Motion: 0.05 px/frame)\n"
            "Continuous Inactivity: 210.0s\n"
            "Flock Proximity: Within flock cluster (0.3m from nearest birds)\n"
            "Physical Signs: Smooth plumage, head tucked under wing, bright red comb, regular breathing, no discharge.\n"
            "Posture & Gait: Sitting in normal roosting posture among flockmates."
        ),
        "output_text": json.dumps({
            "severity": "None",
            "reason": "Extended stationary duration represents healthy physiological sleep/roosting, as indicated by smooth plumage, bright comb, normal posture, and close integration within the flock.",
            "farmer_action": "No intervention required. Allow the bird to continue resting."
        })
    },
    # 2. Normal post-dustbath rest
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #04\n"
            "Stationary Duration: 135.0s (Motion: 0.15 px/frame)\n"
            "Continuous Inactivity: 150.0s\n"
            "Flock Proximity: In communal dustbathing area with 4 flockmates\n"
            "Physical Signs: Plumage slightly fluffed from dry litter dust, clean eyes and nostrils, vibrant comb, alert when sounds occur.\n"
            "Posture & Gait: Reclined on side in dry shavings, occasionally blinking."
        ),
        "output_text": json.dumps({
            "severity": "None",
            "reason": "Immobility is consistent with post-dustbathing relaxation; the bird remains alert with healthy comb color and shows no pathological lethargy.",
            "farmer_action": "No action needed. Maintain dry and fresh dustbathing substrate."
        })
    },
    # 3. Broody hen on nesting box
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #19\n"
            "Stationary Duration: 360.0s (Motion: 0.02 px/frame)\n"
            "Continuous Inactivity: 400.0s\n"
            "Flock Proximity: Nesting box area, solitary by choice\n"
            "Physical Signs: Fluffed body feathers over nest, comb red but slightly dry, sharp vocal growl and defensive pecking posture when approached.\n"
            "Posture & Gait: Flattened tightly against nest floor."
        ),
        "output_text": json.dumps({
            "severity": "Low",
            "reason": "Prolonged stationary period is driven by maternal broodiness rather than systemic lethargy, supported by defensive vocalization and intentional nest-sitting.",
            "farmer_action": "Check nesting box for eggs. If brooding is undesirable, transfer the hen to a well-ventilated broody breaker cage with feed and water."
        })
    },
    # 4. Sunbasking behavior
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #23\n"
            "Stationary Duration: 160.0s (Motion: 0.10 px/frame)\n"
            "Continuous Inactivity: 190.0s\n"
            "Flock Proximity: Under sunbeam patch with 2 other birds\n"
            "Physical Signs: One wing fanned outward against litter, smooth plumage, bright eyes, bright red comb and wattles, rhythmic calm respiration.\n"
            "Posture & Gait: Lying tilted on one side basking in direct natural light."
        ),
        "output_text": json.dumps({
            "severity": "None",
            "reason": "The chicken is engaging in natural sun-basking comfort behavior; prolonged lack of movement with outstretched wing is normal relaxation rather than weakness.",
            "farmer_action": "No action required. Ensure shaded areas and fresh drinking water remain accessible throughout the shed."
        })
    },
    # 5. Mild heat stress immobility
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #08\n"
            "Stationary Duration: 240.0s (Motion: 0.20 px/frame)\n"
            "Continuous Inactivity: 270.0s\n"
            "Flock Proximity: Near side ventilation wall (1.2m spacing)\n"
            "Physical Signs: Wings held slightly away from body, rapid open-beak panting, comb bright red and warm, feathers slightly parted.\n"
            "Posture & Gait: Crouched low near air draft, stationary to minimize metabolic heat generation."
        ),
        "output_text": json.dumps({
            "severity": "Mild",
            "reason": "Stationary behavior is a thermoregulatory adaptation to heat stress; the bird minimizes physical movement while panting and elevating wings to dissipate heat.",
            "farmer_action": "Inspect house temperature and ventilation fans, verify cool water flow at nipple lines, and consider adding electrolytes or vitamin C to drinking water."
        })
    },
    # 6. Post-vaccination / handling fatigue
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #15\n"
            "Stationary Duration: 290.0s (Motion: 0.18 px/frame)\n"
            "Continuous Inactivity: 330.0s\n"
            "Flock Proximity: Edge of pen, semi-isolated\n"
            "Physical Signs: Feathers slightly ruffled around neck, comb slightly dull red, slow blinking, no respiratory sounds or discharge.\n"
            "Posture & Gait: Standing still with head retracted into shoulders, slow hesitant response when birds walk past."
        ),
        "output_text": json.dumps({
            "severity": "Mild",
            "reason": "Mild lethargy and transient reduction in movement likely stemming from vaccination stress or handling exhaustion without severe clinical decline.",
            "farmer_action": "Flag bird for observation over the next 4-6 hours. Ensure unobstructed access to feed and clean water."
        })
    },
    # 7. Early dehydration / water line failure
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #31\n"
            "Stationary Duration: 220.0s (Motion: 0.22 px/frame)\n"
            "Continuous Inactivity: 260.0s\n"
            "Flock Proximity: Lingering beneath inactive nipple drinker line\n"
            "Physical Signs: Comb slightly shriveled and pale pink, dry beak, eyes slightly recessed, feathers somewhat dull.\n"
            "Posture & Gait: Standing motionless looking upward toward drinker line without pecking."
        ),
        "output_text": json.dumps({
            "severity": "Mild",
            "reason": "Reduced mobility and stationary fixation under the water line accompanied by comb pallor and dryness indicate early dehydration and physical fatigue.",
            "farmer_action": "Immediately inspect water line pressure and drinker nipples for blockages; verify water availability across all sections."
        })
    },
    # 8. Mild unilateral leg strain
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #09\n"
            "Stationary Duration: 340.0s (Motion: 0.10 px/frame)\n"
            "Continuous Inactivity: 380.0s\n"
            "Flock Proximity: Near feeder tray (0.8m)\n"
            "Physical Signs: Plumage clean and orderly, bright comb, eyes sharp and alert, right leg tucked up slightly against flank when standing.\n"
            "Posture & Gait: Reluctant to step forward; takes two limping steps before sitting down promptly."
        ),
        "output_text": json.dumps({
            "severity": "Mild",
            "reason": "Prolonged inactivity is caused by musculoskeletal pain/reluctance to bear weight on the right leg rather than systemic illness or infectious lethargy.",
            "farmer_action": "Catch the bird to inspect the right hock and footpad for cuts, splinters, or early swelling; place in a small low-density convalescent pen."
        })
    },
    # 9. Early Coccidiosis sign in growing pullet
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #42\n"
            "Stationary Duration: 380.0s (Motion: 0.11 px/frame)\n"
            "Continuous Inactivity: 430.0s\n"
            "Flock Proximity: Sitting alone 2.5m away from active feeding flock\n"
            "Physical Signs: Ruffled neck and back feathers, slightly drooping wings, comb pale pink, eyes dull with frequent squinting.\n"
            "Posture & Gait: Hunched 'puffed-up' posture with head pulled tight into body, non-responsive to feeder vibrations."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "Abnormal immobility accompanied by a hunched puffed posture, ruffled plumage, pale comb, and flock isolation signals early enteric distress such as coccidiosis.",
            "farmer_action": "Isolate the bird, inspect droppings for blood or mucoid content, and check anticoccidial medication or water sanitation protocols."
        })
    },
    # 10. Moderate lethargy with comb pallor and weakness
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #14\n"
            "Stationary Duration: 450.0s (Motion: 0.08 px/frame)\n"
            "Continuous Inactivity: 510.0s\n"
            "Flock Proximity: Edge of pen near curtain wall (3.0m from group)\n"
            "Physical Signs: Pronounced pale and dry comb, drooping wingtips resting on bedding, unkempt ruffled plumage, half-closed eyes.\n"
            "Posture & Gait: Low crouch, failed to stand when flock moved past during lighting transition."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "Severe lack of spontaneous movement exceeding 7 minutes combined with marked comb pallor, wing drooping, and complete disinterest in flock movement indicates moderate systemic illness.",
            "farmer_action": "Move to an isolated recovery area, check cloaca and body condition score, provide oral electrolyte solution, and test for parasitic or bacterial infection."
        })
    },
    # 11. Pododermatitis (Bumblefoot) Grade 3
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #06\n"
            "Stationary Duration: 520.0s (Motion: 0.04 px/frame)\n"
            "Continuous Inactivity: 580.0s\n"
            "Flock Proximity: In open run away from perches\n"
            "Physical Signs: Black necrotic scab visible on left plantar surface, swollen foot, feathers ruffled around abdomen from prolonged resting on wet litter, comb dull red.\n"
            "Posture & Gait: Sits continuously on sternum; refuses to rise even when nudged by other chickens."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "Pronounced immobility is driven by severe plantar pain from bumblefoot, preventing normal foraging and inducing secondary lethargy.",
            "farmer_action": "Catch bird for foot soak in warm Epsom salt, clean and debride the abscess, bandage with antibacterial ointment, and evaluate shed litter moisture levels."
        })
    },
    # 12. Crop Impaction / Sour Crop
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #27\n"
            "Stationary Duration: 490.0s (Motion: 0.07 px/frame)\n"
            "Continuous Inactivity: 540.0s\n"
            "Flock Proximity: Stationary near feeder without consuming feed\n"
            "Physical Signs: Greatly enlarged, pendulous, fluid-filled crop, sour-smelling oral odor, ruffled feathers, pale comb, occasional regurgitation motion.\n"
            "Posture & Gait: Motionless upright stance with neck repeatedly extending and twisting."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "Lethargy and prolonged stationarity are linked to upper digestive tract obstruction; pendulous crop and neck twisting indicate severe discomfort and inability to ingest nutrition.",
            "farmer_action": "Withhold feed for 12 hours, palpate crop to determine hard vs sour fluid impaction, gently massage, administer oral water/electrolyte, and seek veterinary antifungal/antibiotic guidance."
        })
    },
    # 13. Sub-acute Respiratory Infection (CRD / Mycoplasma)
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #18\n"
            "Stationary Duration: 410.0s (Motion: 0.12 px/frame)\n"
            "Continuous Inactivity: 470.0s\n"
            "Flock Proximity: Isolated behind bell drinker\n"
            "Physical Signs: Slight foamy bubbles in ocular corner, dirty feathers on shoulders from head wiping, tail bobbing rhythmically with respiration, comb dull.\n"
            "Posture & Gait: Sits motionless with head lowered, breathing with visible abdominal effort."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "Stationary state and apathy reflect respiratory compromise, confirmed by tail bobbing, ocular exudate, and fatigue from increased work of breathing.",
            "farmer_action": "Quarantine the bird to prevent flock transmission, inspect air quality and ammonia levels in the shed, and consult poultry veterinarian for targeted antimicrobial therapy."
        })
    },
    # 14. Severe Social Bullying / Peck-Order Trauma
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #35\n"
            "Stationary Duration: 390.0s (Motion: 0.05 px/frame)\n"
            "Continuous Inactivity: 440.0s\n"
            "Flock Proximity: Corner of shed, facing wall away from flock\n"
            "Physical Signs: Extensive feather loss and dried peck marks on crown and neck, comb torn and pale, shivering slightly, eyes wide.\n"
            "Posture & Gait: Crouched tightly against corner wall, freezing motion whenever dominant birds approach."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "The chicken displays learned immobility/tonic immobility and behavioral suppression caused by aggressive peer pecking and physical exhaustion.",
            "farmer_action": "Remove the injured bird to a separate recuperation cage, treat peck wounds with antiseptic spray, and review stocking density and feeder space in the pen."
        })
    },
    # 15. Hypocalcemia / Cage Layer Fatigue / Rickets
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #11\n"
            "Stationary Duration: 560.0s (Motion: 0.02 px/frame)\n"
            "Continuous Inactivity: 610.0s\n"
            "Flock Proximity: Lying on wire/floor away from feed trough\n"
            "Physical Signs: Pliable rubbery beak, soft keel bone, pale shriveled comb, ruffled plumage, clear eyes but motor paralysis of legs.\n"
            "Posture & Gait: Sits flat on hocks with legs splayed, completely unable to rise when stimulated."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "Persistent immobility and inability to stand stem from calcium-phosphorus metabolic depletion or rickets, causing severe skeletal weakness.",
            "farmer_action": "Administer oral calcium supplement and vitamin D3 immediately, place bird on deep soft bedding, and rebalance calcium levels in feed ration."
        })
    },
    # 16. Infectious Coryza with Facial Swelling
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #44\n"
            "Stationary Duration: 480.0s (Motion: 0.06 px/frame)\n"
            "Continuous Inactivity: 530.0s\n"
            "Flock Proximity: Perched on floor near drinker, solitary\n"
            "Physical Signs: Severe unilateral infraorbital sinus swelling, foul-smelling nasal discharge, crusty closed left eyelid, ruffled dirty plumage, pale comb.\n"
            "Posture & Gait: Motionless, holding head low to the ground."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "Complete lack of movement and lethargy result from acute sinus inflammation and visual impairment caused by infectious coryza.",
            "farmer_action": "Strict quarantine of affected bird, flush eye and nares with sterile saline, and initiate flock-wide biosecurity and antimicrobial intervention under veterinary guidance."
        })
    },
    # 17. Acute Egg Binding (Dystocia)
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #03\n"
            "Stationary Duration: 620.0s (Motion: 0.03 px/frame)\n"
            "Continuous Inactivity: 680.0s\n"
            "Flock Proximity: Solitary in nest box apron, 4.0m from feeding flock\n"
            "Physical Signs: Cyanotic dark red comb, wings drooping limp to floor, ruffled dull feathers, vent pulsing rhythmically with visible straining, tail pumping.\n"
            "Posture & Gait: Upright 'penguin-like' posture, stationary and visibly distressed, reluctant to walk."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Prolonged immobility and abnormal upright posture combined with severe abdominal straining and drooping wings are classic clinical indicators of acute egg binding.",
            "farmer_action": "Immediate emergency intervention: isolate in a warm humid recovery box (85-90°F), administer oral calcium liquid, lubricate cloaca with sterile petroleum jelly, and prepare for gentle manual extraction if egg does not pass."
        })
    },
    # 18. Severe Newcastle Disease / Neurotropic Form Suspect
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #50\n"
            "Stationary Duration: 740.0s (Motion: 0.01 px/frame)\n"
            "Continuous Inactivity: 800.0s\n"
            "Flock Proximity: Center of shed, totally unreactive to flock traffic\n"
            "Physical Signs: Head rotated backwards over back (torticollis), bilateral wing drop touching ground, greenish watery feces on vent feathers, dark purple comb.\n"
            "Posture & Gait: Spastic rigidity and severe immobility with periodic involuntary head tremors."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Extreme lack of purposeful movement accompanied by torticollis (twisted neck), bilateral wing paralysis, and cyanotic comb strongly suggests severe neurological viral infection (e.g., Newcastle Disease).",
            "farmer_action": "Immediately isolate bird in sealed biocontainment, notify attending poultry veterinarian and veterinary authority for diagnostic sampling, and halt flock movement."
        })
    },
    # 19. Severe Clinical Coccidiosis (Eimeria tenella)
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #17\n"
            "Stationary Duration: 670.0s (Motion: 0.02 px/frame)\n"
            "Continuous Inactivity: 720.0s\n"
            "Flock Proximity: Corner of pen, huddled against partition wall\n"
            "Physical Signs: Extreme 'puffed-ball' hunched posture, wings drooping completely to litter, eyes shut tight, comb chalk-white, bloody mucoid droppings under vent.\n"
            "Posture & Gait: Completely motionless on sternum; does not flinch or react when approached."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Complete lethargy and profound immobility over 11 minutes paired with bloody diarrhea, severe feather ruffling, and extreme comb pallor signify acute hemorragic coccidiosis with hypovolemia.",
            "farmer_action": "Immediately isolate the bird for supportive care with heat and fluids, initiate emergency flock-wide amprolium or toltrazuril treatment in drinking water, and strip wet litter."
        })
    },
    # 20. Acute Systemic Septicemia / Colibacillosis
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #29\n"
            "Stationary Duration: 910.0s (Motion: 0.01 px/frame)\n"
            "Continuous Inactivity: 960.0s\n"
            "Flock Proximity: Lying flat away from all flock activity\n"
            "Physical Signs: Cyanotic dark blue/purple comb and wattles, extreme hypothermia (cold shanks), ruffled dirty plumage, watery yellow droppings, comatose eye response.\n"
            "Posture & Gait: Sternal recumbency with head collapsed flat onto litter, unresponsive to loud sounds."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Near-total cessation of movement exceeding 15 minutes, moribund state, hypothermia, and cyanosis denote advanced systemic septicemia and imminent circulatory collapse.",
            "farmer_action": "Urgent veterinary evaluation; humanely cull if moribund and unresponsive to prevent suffering, submit carcass for necropsy and bacterial culture, and review biosecurity/water quality."
        })
    },
    # 21. Severe Heat Stroke / Thermal Collapse
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #33\n"
            "Stationary Duration: 580.0s (Motion: 0.03 px/frame)\n"
            "Continuous Inactivity: 620.0s\n"
            "Flock Proximity: Hot side of barn near stagnant air pocket\n"
            "Physical Signs: Limp outstretched wings, shallow agonal panting, tongue protruding from open beak, comb dull purplish-red, eyes glazed, body hot to touch.\n"
            "Posture & Gait: Collapsed on breast with neck extended limp on the floor, incapable of rising."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Physical collapse and severe lethargy indicate life-threatening hyperthermia/heat stroke; the bird has lost neuromuscular coordination and ability to regulate internal temperature.",
            "farmer_action": "Immediately submerge legs and lower body in cool (not ice-cold) water, place bird in front of a gentle fan, administer cold electrolyte drops orally if swallowing reflex is intact, and activate shed evaporative cooling."
        })
    },
    # 22. Severe Bilateral Bumblefoot with Systemic Wasting
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #21\n"
            "Stationary Duration: 820.0s (Motion: 0.02 px/frame)\n"
            "Continuous Inactivity: 890.0s\n"
            "Flock Proximity: Permanently settled in bedding beneath drinker line\n"
            "Physical Signs: Bilateral massive swollen plantar abscesses, necrotic black plugs, severely emaciated breast (knife-like keel), pale comb, soiled breast feathers.\n"
            "Posture & Gait: Refuses to bear any weight on feet; drags body by wing flapping when attempts to move occur."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Chronic and debilitating lack of mobility has resulted in severe muscle wasting and dehydration; the bird is immobilized by excruciating bilateral foot infections.",
            "farmer_action": "Separate immediately into a padded sickbay pen, provide feed and water at chest level, begin systemic antibiotic therapy and wound management, or consider humane euthanasia if joint destruction is irreversible."
        })
    },
    # 23. Advanced Chronic Respiratory Disease (CRD) with Airsacculitis
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #38\n"
            "Stationary Duration: 760.0s (Motion: 0.04 px/frame)\n"
            "Continuous Inactivity: 820.0s\n"
            "Flock Proximity: Isolated in dark rear corner of shed\n"
            "Physical Signs: Severe open-mouth gasping with audible moist tracheal rattles, eyelids swollen shut with caseous pus, disheveled unkempt feathers, comb cyanotic.\n"
            "Posture & Gait: Motionless sitting posture with neck vertically stretched upward during each inspiration."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Complete lack of locomotion and profound lethargy caused by acute asphyxiation and severe pulmonary/airsac infection, confirmed by orthopneic posturing and ocular occlusion.",
            "farmer_action": "Isolate the bird under warm supplemental oxygen/mist if available, clear blocked nares, provide injectable broad-spectrum antibiotic per veterinarian, and evaluate flock-wide ventilation and Mycoplasma status."
        })
    },
    # 24. Egg Yolk Peritonitis (Internal Layer)
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #02\n"
            "Stationary Duration: 860.0s (Motion: 0.02 px/frame)\n"
            "Continuous Inactivity: 910.0s\n"
            "Flock Proximity: Ground level corner, separated from active layers\n"
            "Physical Signs: Distended, fluid-filled, hot abdomen (ascites), pale and dry shriveled comb, dirty matted vent plumage, dull vacant stare.\n"
            "Posture & Gait: Upright stance with dropped rear abdomen touching the floor, entirely stationary."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Prolonged immobility, abnormal abdominal distension, and ruffled matted plumage reflect severe internal egg yolk peritonitis and toxemia.",
            "farmer_action": "Isolate in an intensive care pen, consult a veterinarian for therapeutic abdominocentesis (fluid drainage) and anti-inflammatory/antibiotic regimen, or evaluate prognosis."
        })
    },
    # 25. Severe Red Mite (Dermanyssus gallinae) Anemia
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #47\n"
            "Stationary Duration: 980.0s (Motion: 0.01 px/frame)\n"
            "Continuous Inactivity: 1040.0s\n"
            "Flock Proximity: In floor bedding, unable to reach elevated roost\n"
            "Physical Signs: Completely blanched chalk-white comb, wattles, and eyelids, severe emaciation, dirty ruffled feathers, tiny dark specks crawling on skin near vent.\n"
            "Posture & Gait: Collapsed on breast, motionless and unresponsive to handling."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Severe lethargy and near-paralysis are caused by critical hemorrhagic anemia and weakness from an overwhelming red mite infestation.",
            "farmer_action": "Provide immediate oral iron/vitamin and electrolyte hydration, move bird to an uninfested recovery area, and treat the entire coop structure with approved acaricide (e.g., fluralaner in drinking water or silica dust)."
        })
    },
    # 26. Botulism (Limberneck / Clostridium botulinum intoxication)
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #16\n"
            "Stationary Duration: 640.0s (Motion: 0.01 px/frame)\n"
            "Continuous Inactivity: 700.0s\n"
            "Flock Proximity: Near wet litter depression, solitary\n"
            "Physical Signs: Flaccid paralysis of neck, limp wings spread flat out on litter, third eyelid paralyzed across cornea, feathers easily pulled out, pale comb.\n"
            "Posture & Gait: Inability to hold head erect; head and beak rested limply on ground."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Complete lack of voluntary movement and flaccid paralysis of neck and wing muscles are hallmark signs of botulism neurotoxin ingestion (limberneck).",
            "farmer_action": "Immediately flush crop with warm water or administer activated charcoal if caught early, remove any decomposing carcasses or wet decaying matter from the shed, and provide supportive fluid therapy."
        })
    },
    # 27. Marek's Disease (Neurolymphomatosis)
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #25\n"
            "Stationary Duration: 1120.0s (Motion: 0.01 px/frame)\n"
            "Continuous Inactivity: 1200.0s\n"
            "Flock Proximity: Solitary in walkway\n"
            "Physical Signs: One leg stretched rigidly forward and the opposite leg extended backwards, drooping wing touching ground, irregular grey pupil (ocular lymphomatosis), pale comb.\n"
            "Posture & Gait: Completely paralyzed in classic split-leg position, incapable of locomotion."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Total loss of mobility and progressive asymmetrical limb paralysis with ocular irregularities strongly indicate classical Marek's Disease neurolymphomatosis.",
            "farmer_action": "Humanely euthanize the affected bird to prevent prolonged suffering, send for necropsy confirmation of nerve enlargement, and verify Marek's vaccination protocol for future replacement chicks."
        })
    },
    # 28. Predator Shock and Acute Trauma
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #39\n"
            "Stationary Duration: 420.0s (Motion: 0.02 px/frame)\n"
            "Continuous Inactivity: 460.0s\n"
            "Flock Proximity: Cornered under nesting box frame\n"
            "Physical Signs: Puncture wounds and missing feathers on dorsum, trembling violently, rapid shallow breathing, pale comb, blood smeared on flank.\n"
            "Posture & Gait: Frozen motionless in tonic immobility shock state, head pressed against floor."
        ),
        "output_text": json.dumps({
            "severity": "Severe",
            "reason": "Prolonged freezing/immobility is triggered by acute hypovolemic and psychogenic shock following predator trauma, exacerbated by active lacerations.",
            "farmer_action": "Carefully transfer to a quiet, warm, dimmed infirmary pen, flush and disinfect lacerations with chlorhexidine, control bleeding, and provide warm electrolyte water."
        })
    },
    # 29. Normal Post-Feeding Satiation Rest
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #05\n"
            "Stationary Duration: 110.0s (Motion: 0.14 px/frame)\n"
            "Continuous Inactivity: 130.0s\n"
            "Flock Proximity: Alongside feed hopper with 6 flockmates\n"
            "Physical Signs: Full, rounded, firm crop, bright red comb, sleek glossy feathers, clean dry nostrils, periodic calm blinking.\n"
            "Posture & Gait: Sitting comfortably on clean bedding, digesting quietly with head upright."
        ),
        "output_text": json.dumps({
            "severity": "None",
            "reason": "Stationary duration is standard postprandial resting and digestive idling; the bird exhibits a full healthy crop, bright plumage, and undisturbed social integration.",
            "farmer_action": "No action needed. Maintain scheduled feeding routines and fresh water access."
        })
    },
    # 30. Infectious Bronchitis (Mild/Moderate Respiratory)
    {
        "input_text": (
            "[DETECTION LOG]\n"
            "Track ID: Chicken #22\n"
            "Stationary Duration: 400.0s (Motion: 0.09 px/frame)\n"
            "Continuous Inactivity: 450.0s\n"
            "Flock Proximity: 1.5m away from central flock group\n"
            "Physical Signs: Frequent tracheal sneezing and head shaking, clear serous nasal discharge on beak, ruffled mantle feathers, dull comb.\n"
            "Posture & Gait: Standing in one place with hunched shoulders, reluctant to forage or scratch litter."
        ),
        "output_text": json.dumps({
            "severity": "Moderate",
            "reason": "Reduced activity and persistent stationarity over 6 minutes reflect viral respiratory distress (e.g., Infectious Bronchitis), corroborated by sneezing, nasal exudate, and lethargic posture.",
            "farmer_action": "Isolate symptomatic bird, improve house ventilation while avoiding drafts, optimize brooder/shed temperature, and monitor flock egg shell quality and production."
        })
    }
]

# Write out to CSV in the poultrySense AI directory
output_csv_path = r"c:\Users\kevin\POULTRY\PoultrySense-Andriod-Studio-\poultrysense_ai\gemini_lethargy_detection_tuning.csv"

with open(output_csv_path, mode='w', newline='', encoding='utf-8') as f:
    writer = csv.writer(f, quoting=csv.QUOTE_ALL)
    # Header recognized by Google AI Studio
    writer.writerow(["input_text", "output_text"])
    for row in data_pairs:
        writer.writerow([row["input_text"], row["output_text"]])

print(f"Successfully generated {len(data_pairs)} pairs to {output_csv_path}")
